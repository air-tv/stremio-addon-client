import type { CacheStore } from "@get-air/cache"
import type { HttpTransport } from "@get-air/http"

export type AddonResourceName = "catalog" | "meta" | "stream" | "subtitles" | "addon_catalog"

export interface ManifestResourceDefinition {
  readonly name: string
  readonly types: ReadonlyArray<string>
  readonly idPrefixes?: ReadonlyArray<string>
}

export type ManifestResource = string | ManifestResourceDefinition

export interface CatalogExtraDefinition {
  readonly name: string
  readonly isRequired?: boolean
  readonly options?: ReadonlyArray<string>
  readonly optionsLimit?: number
}

export interface CatalogDefinition {
  readonly type: string
  readonly id: string
  readonly name: string
  readonly extra?: ReadonlyArray<CatalogExtraDefinition>
}

export interface AddonBehaviorHints {
  readonly adult?: boolean
  readonly p2p?: boolean
  readonly configurable?: boolean
  readonly configurationRequired?: boolean
}

export interface AddonManifest {
  readonly id: string
  readonly version: string
  readonly name: string
  readonly description: string
  readonly resources: ReadonlyArray<ManifestResource>
  readonly types: ReadonlyArray<string>
  readonly catalogs: ReadonlyArray<CatalogDefinition>
  readonly idPrefixes?: ReadonlyArray<string>
  readonly logo?: string
  readonly background?: string
  readonly contactEmail?: string
  readonly behaviorHints?: AddonBehaviorHints
}

export interface MetaLink {
  readonly name: string
  readonly category: string
  readonly url: string
}

export interface Trailer {
  readonly source: string
  readonly type: string
}

export interface StreamProxyHeaders {
  readonly request?: Readonly<Record<string, string>>
  readonly response?: Readonly<Record<string, string>>
}

export interface StreamBehaviorHints {
  readonly bingeGroup?: string
  readonly videoHash?: string
  readonly videoSize?: number
  readonly filename?: string
  readonly notWebReady?: boolean
  readonly countryWhitelist?: ReadonlyArray<string>
  readonly proxyHeaders?: StreamProxyHeaders
}

export interface Subtitle {
  readonly id: string
  readonly url: string
  readonly lang: string
}

export interface Stream {
  readonly url?: string
  readonly ytId?: string
  readonly infoHash?: string
  readonly fileIdx?: number
  readonly externalUrl?: string
  readonly name?: string
  readonly title?: string
  readonly description?: string
  readonly sources?: ReadonlyArray<string>
  readonly subtitles?: ReadonlyArray<Subtitle>
  readonly behaviorHints?: StreamBehaviorHints
}

export interface Video {
  readonly id: string
  readonly title: string
  readonly released?: string
  readonly thumbnail?: string
  readonly season?: number
  readonly episode?: number
  readonly overview?: string
  readonly available?: boolean
  readonly streams?: ReadonlyArray<Stream>
}

export interface MetaPreview {
  readonly id: string
  readonly type: string
  readonly name: string
  readonly poster?: string
  readonly posterShape?: "square" | "poster" | "landscape"
  readonly background?: string
  readonly description?: string
  readonly releaseInfo?: string
  readonly imdbRating?: string | number
  readonly genres?: ReadonlyArray<string>
  readonly director?: ReadonlyArray<string>
  readonly cast?: ReadonlyArray<string>
  readonly links?: ReadonlyArray<MetaLink>
  readonly trailers?: ReadonlyArray<Trailer>
}

export interface Meta extends MetaPreview {
  readonly logo?: string
  readonly released?: string
  readonly runtime?: string
  readonly language?: string
  readonly country?: string
  readonly website?: string
  readonly videos?: ReadonlyArray<Video>
}

export interface CacheHints {
  readonly cacheMaxAge?: number
  readonly staleRevalidate?: number
  readonly staleError?: number
}

export interface CatalogResponse extends CacheHints {
  readonly metas: ReadonlyArray<MetaPreview>
}

export interface MetaResponse extends CacheHints {
  readonly meta: Meta
}

export interface StreamResponse extends CacheHints {
  readonly streams: ReadonlyArray<Stream>
}

export interface SubtitlesResponse extends CacheHints {
  readonly subtitles: ReadonlyArray<Subtitle>
}

export interface AddonCatalogItem {
  readonly transportName?: string
  readonly transportUrl: string
  readonly manifest: AddonManifest
}

export interface AddonCatalogResponse extends CacheHints {
  readonly addons: ReadonlyArray<AddonCatalogItem>
}

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
