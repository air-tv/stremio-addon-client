import type { CacheStore } from "@get-air/cache"
import { CacheStoreService, EffectCache } from "@get-air/cache/effect"
import { Effect, Schema } from "effect"
import {
  AddonCacheKeyError,
  type AddonClientError,
  AddonHttpStatusError,
  AddonInvalidJsonError,
  AddonResponseTooLargeError,
  AddonResponseValidationError,
  AddonTimeoutError,
  AddonTransportError,
  InvalidAddonUrlError,
} from "../Errors.js"
import {
  AddonCatalogResponse as AddonCatalogResponseSchema,
  CatalogResponse as CatalogResponseSchema,
  MetaResponse as MetaResponseSchema,
  StreamResponse as StreamResponseSchema,
  SubtitlesResponse as SubtitlesResponseSchema,
} from "../Schemas.js"
import type { AddonCallOptions, KnownAddonResource } from "../Types.js"
import { type NormalizedOptions, validateUrl } from "./Url.js"

class TimeoutMarker extends Error {}
class ResponseTooLargeMarker extends Error {}
const cache = new EffectCache("@get-air/stremio-addon-client/http/v1")

const readBoundedText = async (response: Response, maxBytes: number): Promise<string> => {
  const declared = Number(response.headers.get("content-length"))
  if (Number.isFinite(declared) && declared > maxBytes) throw new ResponseTooLargeMarker()
  if (response.body === null) return ""
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let bytes = 0
  const chunks: string[] = []
  while (true) {
    const part = await reader.read()
    if (part.done) break
    bytes += part.value.byteLength
    if (bytes > maxBytes) {
      await reader.cancel()
      throw new ResponseTooLargeMarker()
    }
    chunks.push(decoder.decode(part.value, { stream: true }))
  }
  chunks.push(decoder.decode())
  return chunks.join("")
}

const requestResponse = (
  url: URL,
  options: NormalizedOptions,
  callOptions: AddonCallOptions,
): Effect.Effect<Response, AddonTransportError | AddonTimeoutError> =>
  Effect.tryPromise({
    try: async (signal) => {
      const timeout = new AbortController()
      const timer = setTimeout(() => timeout.abort(new TimeoutMarker()), options.timeoutMillis)
      try {
        const headers = new Headers(options.headers)
        headers.set("accept", "application/json")
        if (!headers.has("user-agent")) headers.set("user-agent", options.userAgent)
        return await options.transport.fetch(new Request(url, {
          method: "GET",
          headers,
          redirect: "follow",
          signal: AbortSignal.any(callOptions.signal === undefined
            ? [signal, timeout.signal]
            : [signal, callOptions.signal, timeout.signal]),
        }))
      } catch (cause) {
        if (timeout.signal.aborted) throw new TimeoutMarker()
        throw cause
      } finally {
        clearTimeout(timer)
      }
    },
    catch: (cause) => cause instanceof TimeoutMarker
      ? new AddonTimeoutError({
          url: url.toString(),
          timeoutMillis: options.timeoutMillis,
          message: `Addon request timed out after ${options.timeoutMillis}ms`,
        })
      : new AddonTransportError({
          url: url.toString(),
          message: cause instanceof Error ? cause.message : String(cause),
          retryable: callOptions.signal?.aborted !== true,
        }),
  })

const readResponse = (
  url: URL,
  response: Response,
  maxBytes: number,
): Effect.Effect<string, AddonResponseTooLargeError | AddonTransportError> =>
  Effect.tryPromise({
    try: () => readBoundedText(response, maxBytes),
    catch: (cause) => cause instanceof ResponseTooLargeMarker
      ? new AddonResponseTooLargeError({
          url: url.toString(),
          maxResponseBytes: maxBytes,
          message: `Addon response exceeded ${maxBytes} bytes`,
        })
      : new AddonTransportError({
          url: url.toString(),
          message: cause instanceof Error ? cause.message : String(cause),
          retryable: true,
        }),
  })

const cacheKey = (url: URL): Effect.Effect<string, AddonCacheKeyError> =>
  Effect.tryPromise({
    try: async () => {
      const digest = await globalThis.crypto.subtle.digest(
        "SHA-256",
        new TextEncoder().encode(url.toString()),
      )
      return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("")
    },
    catch: (cause) => new AddonCacheKeyError({
      url: url.toString(),
      message: cause instanceof Error ? cause.message : String(cause),
    }),
  })

const readCache = (
  store: CacheStore,
  key: string,
  options: NormalizedOptions,
): Effect.Effect<string | undefined> =>
  cache.get(key).pipe(
    Effect.provideService(CacheStoreService, store),
    Effect.catchTags({
      CacheReadError: (error) => Effect.sync(() => {
        options.onCacheError?.(new Error(error.message))
        return undefined
      }),
      CacheRemoveError: (error) => Effect.sync(() => {
        options.onCacheError?.(new Error(error.message))
        return undefined
      }),
    }),
  )

const writeCache = (
  store: CacheStore,
  key: string,
  value: string,
  ttlMillis: number,
  options: NormalizedOptions,
): Effect.Effect<void> =>
  cache.set(key, value, { ttlMillis }).pipe(
    Effect.provideService(CacheStoreService, store),
    Effect.catchTag("CacheWriteError", (error) =>
      Effect.sync(() => options.onCacheError?.(new Error(error.message)))),
  )

const maxAgeMillis = (response: Response, value: unknown, fallback: number): number | undefined => {
  const cacheControl = (response.headers.get("cache-control") ?? "").toLowerCase()
  if (cacheControl.includes("no-store") || cacheControl.includes("private")) return undefined
  const headerAge = /(?:^|,)\s*max-age=(\d+)/iu.exec(cacheControl)?.[1]
  if (headerAge !== undefined) {
    const ttl = Number(headerAge) * 1_000
    return ttl > 0 ? ttl : undefined
  }
  if (typeof value === "object" && value !== null && "cacheMaxAge" in value &&
      typeof value.cacheMaxAge === "number") {
    const ttl = Math.max(0, value.cacheMaxAge * 1_000)
    return ttl > 0 ? ttl : undefined
  }
  return fallback > 0 ? fallback : undefined
}

const responseSchemas = {
  catalog: CatalogResponseSchema,
  meta: MetaResponseSchema,
  stream: StreamResponseSchema,
  subtitles: SubtitlesResponseSchema,
  addon_catalog: AddonCatalogResponseSchema,
} as const

export const schemaFor = (resource: KnownAddonResource): Schema.Schema.AnyNoContext =>
  responseSchemas[resource]

const decode = <S extends Schema.Schema.AnyNoContext>(
  url: URL,
  resource: string,
  schema: S,
  text: string,
): Effect.Effect<Schema.Schema.Type<S>, AddonInvalidJsonError | AddonResponseValidationError> =>
  Effect.gen(function* () {
    const json = yield* Effect.try({
      try: () => JSON.parse(text) as unknown,
      catch: (cause) => new AddonInvalidJsonError({
        url: url.toString(),
        message: cause instanceof Error ? cause.message : String(cause),
      }),
    })
    return yield* Schema.decodeUnknown(schema)(json).pipe(
      Effect.catchTag("ParseError", (error) =>
        new AddonResponseValidationError({
          url: url.toString(),
          resource,
          message: error.message,
        })),
    )
  })

export const fetchDecoded = <S extends Schema.Schema.AnyNoContext>(
  url: URL,
  resource: string,
  schema: S,
  options: NormalizedOptions,
  callOptions: AddonCallOptions,
): Effect.Effect<Schema.Schema.Type<S>, AddonClientError> =>
  Effect.gen(function* () {
    yield* Effect.try({
      try: () => validateUrl(url, options, false),
      catch: (cause) => cause instanceof InvalidAddonUrlError
        ? cause
        : new InvalidAddonUrlError({ url: url.toString(), message: String(cause) }),
    })
    const key = options.cache === undefined ? undefined : yield* cacheKey(url)
    if (options.cache !== undefined && key !== undefined && callOptions.bypassCache !== true) {
      const cached = yield* readCache(options.cache, key, options)
      if (cached !== undefined) return yield* decode(url, resource, schema, cached)
    }
    const response = yield* requestResponse(url, options, callOptions)
    if (response.status < 200 || response.status >= 300) {
      return yield* new AddonHttpStatusError({
        url: url.toString(),
        status: response.status,
        message: `Addon returned HTTP ${response.status}`,
        retryable: response.status === 408 || response.status === 429 || response.status >= 500,
      })
    }
    if (response.url !== "") {
      yield* Effect.try({
        try: () => validateUrl(response.url, options, false),
        catch: (cause) => cause instanceof InvalidAddonUrlError
          ? cause
          : new InvalidAddonUrlError({ url: response.url, message: String(cause) }),
      })
    }
    const text = yield* readResponse(url, response, options.maxResponseBytes)
    const value = yield* decode(url, resource, schema, text)
    if (options.cache !== undefined && key !== undefined) {
      const ttlMillis = maxAgeMillis(response, value, options.defaultCacheTtlMillis)
      if (ttlMillis !== undefined) yield* writeCache(options.cache, key, text, ttlMillis, options)
    }
    return value
  })
