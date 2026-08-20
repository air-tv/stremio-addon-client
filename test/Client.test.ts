import { MemoryCacheStore } from "@get-air/cache"
import { FunctionHttpTransport } from "@get-air/http"
import { Effect } from "effect"
import { describe, expect, it, vi } from "vitest"
import {
  StremioAddon,
  isStremioAddonError,
  makeResourceUrl,
} from "../src/index.js"
import { StremioAddon as EffectStremioAddon } from "../src/effect.js"

const manifest = {
  id: "org.example.addon",
  version: "1.0.0",
  name: "Example",
  description: "Fixture addon",
  resources: [
    "catalog",
    { name: "stream", types: ["movie", "series"], idPrefixes: ["tt"] },
    { name: "subtitles", types: ["movie", "series"], idPrefixes: ["tt"] },
  ],
  types: ["movie", "series"],
  catalogs: [{ type: "movie", id: "popular", name: "Popular" }],
} as const

const json = (value: unknown, init: ResponseInit = {}): Response =>
  new Response(JSON.stringify(value), {
    ...init,
    headers: { "content-type": "application/json", ...init.headers },
  })

describe("Stremio addon client", () => {
  it("loads a manifest and consumes supported resources through Promises", async () => {
    const requests: Request[] = []
    const transport = FunctionHttpTransport.from(async (request) => {
      requests.push(request)
      if (request.url.endsWith("/manifest.json")) return json(manifest)
      return json({
        streams: [{ url: "https://cdn.example/movie.mp4", name: "1080p" }],
      })
    })

    const addon = await StremioAddon.connect("stremio://addon.example/base", { transport })
    const result = await addon.streams("movie", "tt1254207")

    expect(addon.manifest.id).toBe(manifest.id)
    expect(addon.manifestUrl).toBe("https://addon.example/base/manifest.json")
    expect(result.streams[0]?.url).toBe("https://cdn.example/movie.mp4")
    expect(requests[1]?.url).toBe("https://addon.example/base/stream/movie/tt1254207.json")
  })

  it("uses an injected shared cache without storing addon registrations", async () => {
    let resourceRequests = 0
    const transport = FunctionHttpTransport.from(async (request) => {
      if (request.url.endsWith("/manifest.json")) return json(manifest)
      resourceRequests += 1
      return json(
        { streams: [{ infoHash: "0123456789abcdef", fileIdx: 0 }] },
        { headers: { "cache-control": "public, max-age=60" } },
      )
    })
    const cache = new MemoryCacheStore()
    const addon = await StremioAddon.connect("https://addon.example/manifest.json", {
      transport,
      cache,
    })

    await addon.streams("movie", "tt1254207")
    await addon.streams("movie", "tt1254207")
    expect(resourceRequests).toBe(1)
  })

  it("honors no-store responses", async () => {
    let resourceRequests = 0
    const transport = FunctionHttpTransport.from(async (request) => {
      if (request.url.endsWith("/manifest.json")) return json(manifest)
      resourceRequests += 1
      return json(
        { streams: [] },
        { headers: { "cache-control": "no-store" } },
      )
    })
    const addon = await StremioAddon.connect("https://addon.example/manifest.json", {
      transport,
      cache: new MemoryCacheStore(),
    })
    await addon.streams("movie", "tt1254207")
    await addon.streams("movie", "tt1254207")
    expect(resourceRequests).toBe(2)
  })

  it("skips cache-key hashing when caching is disabled", async () => {
    const digest = vi.spyOn(globalThis.crypto.subtle, "digest").mockRejectedValue(new Error("unused"))
    const transport = FunctionHttpTransport.from(async () => json(manifest))
    try {
      expect((await StremioAddon.connect("https://addon.example/manifest.json", { transport })).manifest.id)
        .toBe(manifest.id)
    } finally {
      digest.mockRestore()
    }
  })

  it("checks manifest capabilities before making a request", async () => {
    let requests = 0
    const transport = FunctionHttpTransport.from(async () => {
      requests += 1
      return json(manifest)
    })
    const addon = await StremioAddon.connect("https://addon.example/manifest.json", { transport })

    await expect(addon.meta("movie", "tt1254207")).rejects.toMatchObject({
      _tag: "AddonResourceUnsupportedError",
    })
    expect(requests).toBe(1)
  })

  it("rejects unsafe URLs and malformed resource payloads with plain tagged errors", async () => {
    const transport = FunctionHttpTransport.from(async (request) =>
      request.url.endsWith("/manifest.json")
        ? json(manifest)
        : json({ streams: [{ name: "not playable" }] }),
    )
    await expect(StremioAddon.connect("http://addon.example/manifest.json")).rejects.toSatisfy(
      isStremioAddonError,
    )
    await expect(StremioAddon.connect("https://127.0.0.1/manifest.json")).rejects.toSatisfy(
      isStremioAddonError,
    )
    await expect(StremioAddon.connect("https://[::ffff:7f00:1]/manifest.json")).rejects.toSatisfy(
      isStremioAddonError,
    )
    await expect(StremioAddon.connect("https://[::ffff:808:808]/manifest.json", { transport }))
      .resolves.toBeDefined()
    const addon = await StremioAddon.connect("https://addon.example/manifest.json", { transport })
    await expect(addon.streams("movie", "tt1254207")).rejects.toMatchObject({
      _tag: "AddonResponseValidationError",
    })
  })

  it("bounds response bodies using Content-Length before reading", async () => {
    const transport = FunctionHttpTransport.from(async (request) =>
      request.url.endsWith("/manifest.json")
        ? json(manifest)
        : json(
            { streams: [] },
            { headers: { "content-length": "99999", "content-type": "application/json" } },
          ),
    )
    const addon = await StremioAddon.connect("https://addon.example/manifest.json", {
      transport,
      maxResponseBytes: 5_000,
    })
    await expect(addon.streams("movie", "tt1254207")).rejects.toMatchObject({
      _tag: "AddonResponseTooLargeError",
    })
  })

  it("exposes the same implementation as an Effect-native API", async () => {
    const transport = FunctionHttpTransport.from(async (request) =>
      request.url.endsWith("/manifest.json")
        ? json(manifest)
        : json({ streams: [] }),
    )
    const result = await Effect.runPromise(
      Effect.gen(function* () {
        const addon = yield* EffectStremioAddon.connect(
          "https://addon.example/manifest.json",
          { transport },
        )
        return yield* addon.streams("movie", "tt1254207")
      }),
    )
    expect(result.streams).toEqual([])
  })
})

describe("Stremio request URLs", () => {
  it("encodes path components and deterministic repeated extras", () => {
    expect(makeResourceUrl("https://addon.example/manifest.json", {
      resource: "catalog",
      type: "movie",
      id: "popular films",
      extra: { skip: 20, genre: ["Drama", "Sci Fi"] },
    })).toBe(
      "https://addon.example/catalog/movie/popular%20films/genre=Drama&genre=Sci+Fi&skip=20.json",
    )
  })
})
