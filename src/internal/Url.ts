import type { CacheStore } from "@get-air/cache"
import { FunctionHttpTransport } from "@get-air/http"
import { InvalidAddonUrlError } from "../Errors.js"
import type {
  AddonClientOptions,
  AddonExtra,
  AddonManifest,
  AddonRequest,
} from "../Types.js"

const DEFAULT_TIMEOUT_MILLIS = 15_000
const DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024
const DEFAULT_CACHE_TTL_MILLIS = 5 * 60 * 1_000

export interface NormalizedOptions {
  readonly transport: NonNullable<AddonClientOptions["transport"]>
  readonly cache?: CacheStore
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

export const normalizeOptions = (options: AddonClientOptions): NormalizedOptions => ({
  transport: options.transport ?? FunctionHttpTransport.global(),
  ...(options.cache === undefined ? {} : { cache: options.cache }),
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

const isPrivateIpv4 = (octets: readonly number[]): boolean => {
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

const isPrivateHostname = (hostname: string): boolean => {
  const normalized = hostname.replace(/^\[|\]$/gu, "").toLowerCase()
  if (normalized === "localhost" || normalized.endsWith(".localhost")) return true
  const mapped = /^::ffff:([\da-f]{1,4}):([\da-f]{1,4})$/u.exec(normalized)
  if (mapped?.[1] !== undefined && mapped[2] !== undefined) {
    const high = Number.parseInt(mapped[1], 16)
    const low = Number.parseInt(mapped[2], 16)
    return isPrivateIpv4([high >>> 8, high & 255, low >>> 8, low & 255])
  }
  if (normalized.includes(":")) {
    const first = Number.parseInt(normalized.split(":").find(Boolean) ?? "0", 16)
    return normalized === "::" || normalized === "::1" ||
      (first & 0xfe00) === 0xfc00 || (first & 0xffc0) === 0xfe80 || (first & 0xff00) === 0xff00
  }
  return isPrivateIpv4(normalized.split(".").map(Number))
}

export const validateUrl = (
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
  return manifest.resources.some((candidate) => {
    if ((typeof candidate === "string" ? candidate : candidate.name) !== resource) return false
    const types = typeof candidate === "string" ? manifest.types : candidate.types
    const prefixes = typeof candidate === "string" ? manifest.idPrefixes : candidate.idPrefixes
    return types.includes(type) &&
      (prefixes === undefined || prefixes.length === 0 || prefixes.some((prefix) => id.startsWith(prefix)))
  })
}
