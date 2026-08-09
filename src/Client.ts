import { Cache } from "@get-air/cache"
import { FunctionHttpTransport } from "@get-air/http"
import { Effect, Schema } from "effect"
import {
  AddonCacheKeyError,
  AddonCacheReadError,
  AddonCacheWriteError,
  type AddonClientError,
  AddonHttpStatusError,
  AddonInvalidJsonError,
  AddonResourceUnsupportedError,
  AddonResponseTooLargeError,
  AddonResponseValidationError,
  AddonTimeoutError,
  AddonTransportError,
  InvalidAddonUrlError,
} from "./Errors.js"
import {
  AddonCatalogResponse as AddonCatalogResponseSchema,
  AddonManifest as AddonManifestSchema,
  CatalogResponse as CatalogResponseSchema,
  MetaResponse as MetaResponseSchema,
  StreamResponse as StreamResponseSchema,
  SubtitlesResponse as SubtitlesResponseSchema,
} from "./Schemas.js"
import type {
  AddonCallOptions,
  AddonClientOptions,
  AddonExtra,
  AddonManifest,
  AddonRequest,
  AddonResourceResponseMap,
  CatalogResponse,
  KnownAddonResource,
  MetaResponse,
  StreamResponse,
  SubtitlesResponse,
} from "./Types.js"

const DEFAULT_TIMEOUT_MILLIS = 15_000
const DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
const DEFAULT_CACHE_TTL_MILLIS = 5 * 60 * 1_000
const CACHE_NAMESPACE = "@get-air/stremio-addon-client/http/v1"

interface NormalizedOptions {
  readonly transport: NonNullable<AddonClientOptions["transport"]>
  readonly cache?: Cache
  readonly timeoutMillis: number
  readonly maxResponseBytes: number
  readonly defaultCacheTtlMillis: number
  readonly allowHttp: boolean
  readonly allowPrivateNetwork: boolean
  readonly allowedOrigins?: ReadonlySet<string>
  readonly headers: Readonly<Record<string, string>>
  readonly userAgent: string
  readonly urlPolicy?: (url: URL) => boolean
  readonly onCacheError?: (error: Error) => void
}

class TimeoutMarker extends Error {}
class ResponseTooLargeMarker extends Error {}

const normalizeOptions = (options: AddonClientOptions): NormalizedOptions => ({
  transport: options.transport ?? FunctionHttpTransport.global(),
  ...(options.cache === undefined ? {} : { cache: new Cache(options.cache, CACHE_NAMESPACE) }),
  timeoutMillis: options.timeoutMillis ?? DEFAULT_TIMEOUT_MILLIS,
  maxResponseBytes: options.maxResponseBytes ?? DEFAULT_MAX_RESPONSE_BYTES,
  defaultCacheTtlMillis: options.defaultCacheTtlMillis ?? DEFAULT_CACHE_TTL_MILLIS,
  allowHttp: options.allowHttp ?? false,
  allowPrivateNetwork: options.allowPrivateNetwork ?? false,
  ...(options.allowedOrigins === undefined
    ? {}
    : { allowedOrigins: new Set(options.allowedOrigins.map((origin) => new URL(origin).origin)) }),
  headers: options.headers ?? {},
  userAgent: options.userAgent ?? "@get-air/stremio-addon-client/0.1",
  ...(options.urlPolicy === undefined ? {} : { urlPolicy: options.urlPolicy }),
  ...(options.onCacheError === undefined ? {} : { onCacheError: options.onCacheError }),
})

const isPrivateHostname = (hostname: string): boolean => {
  const normalized = hostname.replace(/^\[|\]$/gu, "").toLowerCase()
  if (normalized === "localhost" || normalized.endsWith(".localhost")) return true
  if (normalized === "::1" || (normalized.includes(":") &&
      (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")))) {
    return true
  }
  const octets = normalized.split(".").map(Number)
  if (octets.length !== 4 || octets.some((part) => !Number.isInteger(part) || part < 0 || part > 255)) {
    return false
  }
  const [first, second] = octets as [number, number, number, number]
  return first === 10 || first === 127 || first === 0 ||
    (first === 100 && second >= 64 && second <= 127) ||
    (first === 169 && second === 254) ||
    (first === 172 && second >= 16 && second <= 31) ||
    (first === 192 && (second === 0 || second === 168)) ||
    (first === 198 && (second === 18 || second === 19)) ||
    first >= 224
}

const validateUrl = (
  input: string | URL,
  options: NormalizedOptions,
  requireManifest: boolean,
): URL => {
  const raw = String(input)
  try {
    const parsed = new URL(raw)
    const url = parsed.protocol === "stremio:"
      ? new URL(`https://${parsed.host}${parsed.pathname}${parsed.search}`)
      : parsed
    if (url.protocol !== "https:" && !(options.allowHttp && url.protocol === "http:")) {
      throw new Error("Only HTTPS addon URLs are allowed unless allowHttp is enabled")
    }
    if (url.username !== "" || url.password !== "") {
      throw new Error("Credentials in addon URL authorities are not allowed")
    }
    if (!options.allowPrivateNetwork && isPrivateHostname(url.hostname)) {
      throw new Error("Private-network addon URLs require allowPrivateNetwork")
    }
    if (requireManifest && !url.pathname.endsWith("/manifest.json")) {
      url.pathname = `${url.pathname.replace(/\/$/u, "")}/manifest.json`
    }
    if (options.allowedOrigins !== undefined && !options.allowedOrigins.has(url.origin)) {
      throw new Error(`Origin ${url.origin} is not allowed`)
    }
    if (options.urlPolicy !== undefined && !options.urlPolicy(new URL(url))) {
      throw new Error("The application URL policy rejected this addon URL")
    }
    url.hash = ""
    return url
  } catch (cause) {
    if (cause instanceof InvalidAddonUrlError) throw cause
    throw new InvalidAddonUrlError({
      url: raw,
      message: cause instanceof Error ? cause.message : String(cause),
    })
  }
}

export const normalizeManifestUrl = (
  input: string | URL,
  options: AddonClientOptions = {},
): string => validateUrl(input, normalizeOptions(options), true).toString()

const encodeExtra = (extra: AddonExtra | undefined): string | undefined => {
  if (extra === undefined) return undefined
  const params = new URLSearchParams()
  for (const key of Object.keys(extra).sort()) {
    const value = extra[key]
    if (value === undefined) continue
    const values = Array.isArray(value) ? value : [value]
    for (const item of values) params.append(key, String(item))
  }
  const encoded = params.toString()
  return encoded.length === 0 ? undefined : encoded
}

export const makeResourceUrl = (
  manifestUrl: string | URL,
  request: AddonRequest,
): string => {
  const url = new URL(manifestUrl)
  const basePath = url.pathname.replace(/\/manifest\.json$/u, "")
  const segments = [request.resource, request.type, request.id].map(encodeURIComponent)
  const extra = encodeExtra(request.extra)
  url.pathname = `${basePath}/${segments.join("/")}${extra === undefined ? "" : `/${extra}`}.json`
  url.search = ""
  url.hash = ""
  return url.toString()
}

export const isResourceSupported = (
  manifest: AddonManifest,
  resource: string,
  type: string,
  id: string,
): boolean => {
  if (resource === "catalog") {
    return manifest.catalogs.some((catalog) => catalog.type === type && catalog.id === id)
  }
  const matches = manifest.resources.filter((candidate) =>
    typeof candidate === "string" ? candidate === resource : candidate.name === resource,
  )
  return matches.some((candidate) => {
    const types = typeof candidate === "string" ? manifest.types : candidate.types
    const prefixes = typeof candidate === "string" ? manifest.idPrefixes : candidate.idPrefixes
    return types.includes(type) &&
      (prefixes === undefined || prefixes.length === 0 || prefixes.some((prefix) => id.startsWith(prefix)))
  })
}

const readBoundedText = async (response: Response, maxBytes: number): Promise<string> => {
  const declared = Number(response.headers.get("content-length"))
  if (Number.isFinite(declared) && declared > maxBytes) throw new ResponseTooLargeMarker()
  if (response.body === null) return ""

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let bytes = 0
  let text = ""
  while (true) {
    const part = await reader.read()
    if (part.done) break
    bytes += part.value.byteLength
    if (bytes > maxBytes) {
      await reader.cancel()
      throw new ResponseTooLargeMarker()
    }
    text += decoder.decode(part.value, { stream: true })
  }
  return text + decoder.decode()
}

const requestResponse = (
  url: URL,
  options: NormalizedOptions,
  callOptions: AddonCallOptions,
): Effect.Effect<Response, AddonTransportError | AddonTimeoutError> =>
  Effect.tryPromise({
    try: async () => {
      const controller = new AbortController()
      let timedOut = false
      const abort = () => controller.abort(callOptions.signal?.reason)
      callOptions.signal?.addEventListener("abort", abort, { once: true })
      const timer = setTimeout(() => {
        timedOut = true
        controller.abort(new TimeoutMarker())
      }, options.timeoutMillis)
      try {
        const headers = new Headers(options.headers)
        headers.set("accept", "application/json")
        if (!headers.has("user-agent")) headers.set("user-agent", options.userAgent)
        return await options.transport.fetch(new Request(url, {
          method: "GET",
          headers,
          redirect: "follow",
          signal: controller.signal,
        }))
      } catch (cause) {
        if (timedOut) throw new TimeoutMarker()
        throw cause
      } finally {
        clearTimeout(timer)
        callOptions.signal?.removeEventListener("abort", abort)
      }
    },
    catch: (cause) =>
      cause instanceof TimeoutMarker
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
    catch: (cause) =>
      cause instanceof ResponseTooLargeMarker
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
    catch: (cause) =>
      new AddonCacheKeyError({
        url: url.toString(),
        message: cause instanceof Error ? cause.message : String(cause),
      }),
  })

const readCache = (
  cache: Cache,
  key: string,
  options: NormalizedOptions,
): Effect.Effect<string | undefined> =>
  Effect.tryPromise({
    try: () => cache.get(key),
    catch: (cause) => new AddonCacheReadError({
      key,
      message: cause instanceof Error ? cause.message : String(cause),
    }),
  }).pipe(
    Effect.catchTag("AddonCacheReadError", (error) =>
      Effect.sync(() => {
        options.onCacheError?.(new Error(error.message))
        return undefined
      })),
  )

const writeCache = (
  cache: Cache,
  key: string,
  value: string,
  ttlMillis: number,
  options: NormalizedOptions,
): Effect.Effect<void> =>
  Effect.tryPromise({
    try: () => cache.set(key, value, { ttlMillis }),
    catch: (cause) => new AddonCacheWriteError({
      key,
      message: cause instanceof Error ? cause.message : String(cause),
    }),
  }).pipe(
    Effect.catchTag("AddonCacheWriteError", (error) =>
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

const schemaFor = (resource: KnownAddonResource) => {
  switch (resource) {
    case "catalog": return CatalogResponseSchema
    case "meta": return MetaResponseSchema
    case "stream": return StreamResponseSchema
    case "subtitles": return SubtitlesResponseSchema
    case "addon_catalog": return AddonCatalogResponseSchema
  }
}

const decode = <A>(
  url: URL,
  resource: string,
  schema: Schema.Schema<any, any, never>,
  text: string,
): Effect.Effect<A, AddonInvalidJsonError | AddonResponseValidationError> =>
  Effect.gen(function* () {
    const json = yield* Effect.try({
      try: () => JSON.parse(text) as unknown,
      catch: (cause) => new AddonInvalidJsonError({
        url: url.toString(),
        message: cause instanceof Error ? cause.message : String(cause),
      }),
    })
    const decoded = yield* Schema.decodeUnknown(schema)(json).pipe(
      Effect.catchTag("ParseError", (error) =>
        Effect.fail(new AddonResponseValidationError({
          url: url.toString(),
          resource,
          message: error.message,
        }))),
    )
    return decoded as A
  })

const fetchDecoded = <A>(
  url: URL,
  resource: string,
  schema: Schema.Schema<any, any, never>,
  options: NormalizedOptions,
  callOptions: AddonCallOptions,
): Effect.Effect<A, AddonClientError> =>
  Effect.gen(function* () {
    yield* Effect.try({
      try: () => validateUrl(url, options, false),
      catch: (cause) => cause instanceof InvalidAddonUrlError
        ? cause
        : new InvalidAddonUrlError({ url: url.toString(), message: String(cause) }),
    })
    const key = yield* cacheKey(url)
    if (options.cache !== undefined && callOptions.bypassCache !== true) {
      const cached = yield* readCache(options.cache, key, options)
      if (cached !== undefined) return yield* decode<A>(url, resource, schema, cached)
    }

    const response = yield* requestResponse(url, options, callOptions)
    if (response.status < 200 || response.status >= 300) {
      return yield* Effect.fail(new AddonHttpStatusError({
        url: url.toString(),
        status: response.status,
        message: `Addon returned HTTP ${response.status}`,
        retryable: response.status === 408 || response.status === 429 || response.status >= 500,
      }))
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
    const value = yield* decode<A>(url, resource, schema, text)
    if (options.cache !== undefined) {
      const ttlMillis = maxAgeMillis(response, value, options.defaultCacheTtlMillis)
      if (ttlMillis !== undefined) {
        yield* writeCache(options.cache, key, text, ttlMillis, options)
      }
    }
    return value
  })

export interface StremioAddonClientShape {
  readonly manifest: AddonManifest
  readonly manifestUrl: string
  readonly isSupported: (resource: string, type: string, id: string) => boolean
  readonly request: <Resource extends KnownAddonResource>(
    request: AddonRequest<Resource>,
    options?: AddonCallOptions,
  ) => Effect.Effect<AddonResourceResponseMap[Resource], AddonClientError>
  readonly catalog: (
    type: string,
    id: string,
    extra?: AddonExtra,
    options?: AddonCallOptions,
  ) => Effect.Effect<CatalogResponse, AddonClientError>
  readonly meta: (
    type: string,
    id: string,
    options?: AddonCallOptions,
  ) => Effect.Effect<MetaResponse, AddonClientError>
  readonly streams: (
    type: string,
    id: string,
    options?: AddonCallOptions,
  ) => Effect.Effect<StreamResponse, AddonClientError>
  readonly subtitles: (
    type: string,
    id: string,
    extra?: AddonExtra,
    options?: AddonCallOptions,
  ) => Effect.Effect<SubtitlesResponse, AddonClientError>
}

export const makeStremioAddonClient = Effect.fn("StremioAddonClient.make")(function* (
  input: string | URL,
  clientOptions: AddonClientOptions = {},
): Effect.fn.Return<StremioAddonClientShape, AddonClientError> {
  const options = yield* Effect.try({
    try: () => normalizeOptions(clientOptions),
    catch: (cause) => new InvalidAddonUrlError({
      url: String(input),
      message: cause instanceof Error ? cause.message : String(cause),
    }),
  })
  const manifestUrl = yield* Effect.try({
    try: () => validateUrl(input, options, true),
    catch: (cause) => cause instanceof InvalidAddonUrlError
      ? cause
      : new InvalidAddonUrlError({ url: String(input), message: String(cause) }),
  })
  const manifest = yield* fetchDecoded<AddonManifest>(
    manifestUrl,
    "manifest",
    AddonManifestSchema,
    options,
    {},
  )

  const request = Effect.fn("StremioAddonClient.request")(function* <
    Resource extends KnownAddonResource,
  >(
    addonRequest: AddonRequest<Resource>,
    callOptions: AddonCallOptions = {},
  ): Effect.fn.Return<AddonResourceResponseMap[Resource], AddonClientError> {
    if (!isResourceSupported(
      manifest,
      addonRequest.resource,
      addonRequest.type,
      addonRequest.id,
    )) {
      return yield* Effect.fail(new AddonResourceUnsupportedError({
        resource: addonRequest.resource,
        type: addonRequest.type,
        id: addonRequest.id,
        message: `Addon does not support ${addonRequest.resource}/${addonRequest.type}/${addonRequest.id}`,
      }))
    }
    const url = new URL(makeResourceUrl(manifestUrl, addonRequest))
    return yield* fetchDecoded<AddonResourceResponseMap[Resource]>(
      url,
      addonRequest.resource,
      schemaFor(addonRequest.resource),
      options,
      callOptions,
    ) as Effect.Effect<AddonResourceResponseMap[Resource], AddonClientError>
  })

  return {
    manifest,
    manifestUrl: manifestUrl.toString(),
    isSupported: (resource, type, id) => isResourceSupported(manifest, resource, type, id),
    request,
    catalog: (type, id, extra, callOptions) =>
      request({ resource: "catalog", type, id, ...(extra === undefined ? {} : { extra }) }, callOptions),
    meta: (type, id, callOptions) => request({ resource: "meta", type, id }, callOptions),
    streams: (type, id, callOptions) => request({ resource: "stream", type, id }, callOptions),
    subtitles: (type, id, extra, callOptions) =>
      request({ resource: "subtitles", type, id, ...(extra === undefined ? {} : { extra }) }, callOptions),
  }
})

export class StremioAddon {
  readonly manifest: AddonManifest
  readonly manifestUrl: string
  readonly isSupported: StremioAddonClientShape["isSupported"]
  readonly request: StremioAddonClientShape["request"]
  readonly catalog: StremioAddonClientShape["catalog"]
  readonly meta: StremioAddonClientShape["meta"]
  readonly streams: StremioAddonClientShape["streams"]
  readonly subtitles: StremioAddonClientShape["subtitles"]

  private constructor(client: StremioAddonClientShape) {
    this.manifest = client.manifest
    this.manifestUrl = client.manifestUrl
    this.isSupported = client.isSupported
    this.request = client.request
    this.catalog = client.catalog
    this.meta = client.meta
    this.streams = client.streams
    this.subtitles = client.subtitles
  }

  static readonly connect = Effect.fn("StremioAddon.connect")(function* (
    input: string | URL,
    options: AddonClientOptions = {},
  ) {
    return new StremioAddon(yield* makeStremioAddonClient(input, options))
  })

}
