import type { CacheStore } from "@get-air/cache"
import type { HttpTransport } from "@get-air/http"
import type { Schema } from "effect"
import type * as Schemas from "./Schemas.js"

export type AddonResourceName = "catalog" | "meta" | "stream" | "subtitles" | "addon_catalog"
export type ManifestResourceDefinition = Schema.Schema.Type<typeof Schemas.ManifestResourceDefinition>
export type ManifestResource = Schema.Schema.Type<typeof Schemas.ManifestResource>
export type CatalogExtraDefinition = Schema.Schema.Type<typeof Schemas.CatalogExtraDefinition>
export type CatalogDefinition = Schema.Schema.Type<typeof Schemas.CatalogDefinition>
export type AddonBehaviorHints = Schema.Schema.Type<typeof Schemas.AddonBehaviorHints>
export type AddonManifest = Schema.Schema.Type<typeof Schemas.AddonManifest>
export type MetaLink = Schema.Schema.Type<typeof Schemas.MetaLink>
export type Trailer = Schema.Schema.Type<typeof Schemas.Trailer>
export type StreamProxyHeaders = Schema.Schema.Type<typeof Schemas.StreamProxyHeaders>
export type StreamBehaviorHints = Schema.Schema.Type<typeof Schemas.StreamBehaviorHints>
export type Subtitle = Schema.Schema.Type<typeof Schemas.Subtitle>
export type Stream = Schema.Schema.Type<typeof Schemas.Stream>
export type Video = Schema.Schema.Type<typeof Schemas.Video>
export type MetaPreview = Schema.Schema.Type<typeof Schemas.MetaPreview>
export type Meta = Schema.Schema.Type<typeof Schemas.Meta>

export interface CacheHints {
  readonly cacheMaxAge?: number
  readonly staleRevalidate?: number
  readonly staleError?: number
}

export type CatalogResponse = Schema.Schema.Type<typeof Schemas.CatalogResponse>
export type MetaResponse = Schema.Schema.Type<typeof Schemas.MetaResponse>
export type StreamResponse = Schema.Schema.Type<typeof Schemas.StreamResponse>
export type SubtitlesResponse = Schema.Schema.Type<typeof Schemas.SubtitlesResponse>
export type AddonCatalogItem = Schema.Schema.Type<typeof Schemas.AddonCatalogItem>
export type AddonCatalogResponse = Schema.Schema.Type<typeof Schemas.AddonCatalogResponse>

export interface AddonResourceResponseMap {
  readonly catalog: CatalogResponse
  readonly meta: MetaResponse
  readonly stream: StreamResponse
  readonly subtitles: SubtitlesResponse
  readonly addon_catalog: AddonCatalogResponse
}

export type KnownAddonResource = keyof AddonResourceResponseMap
export type AddonExtraValue = string | number | boolean | ReadonlyArray<string | number | boolean>
export type AddonExtra = Readonly<Record<string, AddonExtraValue | undefined>>

export interface AddonRequest<Resource extends KnownAddonResource = KnownAddonResource> {
  readonly resource: Resource
  readonly type: string
  readonly id: string
  readonly extra?: AddonExtra
}

export interface AddonCallOptions {
  readonly signal?: AbortSignal
  readonly bypassCache?: boolean
}

export interface AddonClientOptions {
  readonly transport?: HttpTransport
  readonly cache?: CacheStore
  readonly timeoutMillis?: number
  readonly maxResponseBytes?: number
  readonly defaultCacheTtlMillis?: number
  readonly allowHttp?: boolean
  readonly allowPrivateNetwork?: boolean
  readonly allowedOrigins?: ReadonlyArray<string>
  readonly headers?: Readonly<Record<string, string>>
  readonly userAgent?: string
  readonly urlPolicy?: (url: URL) => boolean
  readonly onCacheError?: (error: Error) => void
}
