import { Schema } from "effect"

const NonEmpty = Schema.String.pipe(Schema.minLength(1))
const StringArray = Schema.Array(Schema.String)
const CacheHints = {
  cacheMaxAge: Schema.optional(Schema.Number.pipe(Schema.nonNegative())),
  staleRevalidate: Schema.optional(Schema.Number.pipe(Schema.nonNegative())),
  staleError: Schema.optional(Schema.Number.pipe(Schema.nonNegative())),
}

export const ManifestResourceDefinition = Schema.Struct({
  name: NonEmpty,
  types: Schema.Array(NonEmpty),
  idPrefixes: Schema.optional(StringArray),
})

export const ManifestResource = Schema.Union(NonEmpty, ManifestResourceDefinition)

export const CatalogExtraDefinition = Schema.Struct({
  name: NonEmpty,
  isRequired: Schema.optional(Schema.Boolean),
  options: Schema.optional(StringArray),
  optionsLimit: Schema.optional(Schema.Number.pipe(Schema.int(), Schema.positive())),
})

export const CatalogDefinition = Schema.Struct({
  type: NonEmpty,
  id: NonEmpty,
  name: NonEmpty,
  extra: Schema.optional(Schema.Array(CatalogExtraDefinition)),
})

export const AddonBehaviorHints = Schema.Struct({
  adult: Schema.optional(Schema.Boolean),
  p2p: Schema.optional(Schema.Boolean),
  configurable: Schema.optional(Schema.Boolean),
  configurationRequired: Schema.optional(Schema.Boolean),
})

export const AddonManifest = Schema.Struct({
  id: NonEmpty,
  version: NonEmpty,
  name: NonEmpty,
  description: Schema.optionalWith(Schema.String, { default: () => "" }),
  resources: Schema.Array(ManifestResource).pipe(Schema.minItems(1)),
  types: Schema.Array(NonEmpty).pipe(Schema.minItems(1)),
  catalogs: Schema.Array(CatalogDefinition),
  idPrefixes: Schema.optional(StringArray),
  logo: Schema.optional(Schema.String),
  background: Schema.optional(Schema.String),
  contactEmail: Schema.optional(Schema.String),
  behaviorHints: Schema.optional(AddonBehaviorHints),
})

export const Subtitle = Schema.Struct({
  id: Schema.optionalWith(NonEmpty, { default: () => "default" }),
  url: NonEmpty,
  lang: NonEmpty,
})

export const StreamProxyHeaders = Schema.Struct({
  request: Schema.optional(Schema.Record({ key: Schema.String, value: Schema.String })),
  response: Schema.optional(Schema.Record({ key: Schema.String, value: Schema.String })),
})

export const StreamBehaviorHints = Schema.Struct({
  bingeGroup: Schema.optional(Schema.String),
  videoHash: Schema.optional(Schema.String),
  videoSize: Schema.optional(Schema.Number.pipe(Schema.nonNegative())),
  filename: Schema.optional(Schema.String),
  notWebReady: Schema.optional(Schema.Boolean),
  countryWhitelist: Schema.optional(StringArray),
  proxyHeaders: Schema.optional(StreamProxyHeaders),
})

export const Stream = Schema.Struct({
  url: Schema.optional(Schema.String),
  ytId: Schema.optional(Schema.String),
  infoHash: Schema.optional(Schema.String),
  fileIdx: Schema.optional(Schema.Number.pipe(Schema.int(), Schema.nonNegative())),
  externalUrl: Schema.optional(Schema.String),
  name: Schema.optional(Schema.String),
  title: Schema.optional(Schema.String),
  description: Schema.optional(Schema.String),
  sources: Schema.optional(StringArray),
  subtitles: Schema.optional(Schema.Array(Subtitle)),
  behaviorHints: Schema.optional(StreamBehaviorHints),
}).pipe(
  Schema.filter(
    (stream) =>
      stream.url !== undefined ||
      stream.ytId !== undefined ||
      stream.infoHash !== undefined ||
      stream.externalUrl !== undefined,
    { message: () => "A stream must contain url, ytId, infoHash, or externalUrl" },
  ),
)

export const MetaLink = Schema.Struct({ name: NonEmpty, category: NonEmpty, url: NonEmpty })
export const Trailer = Schema.Struct({ source: NonEmpty, type: NonEmpty })

const MetaFields = {
  id: NonEmpty,
  type: NonEmpty,
  name: NonEmpty,
  poster: Schema.optional(Schema.String),
  posterShape: Schema.optional(Schema.Literal("square", "poster", "landscape")),
  background: Schema.optional(Schema.String),
  description: Schema.optional(Schema.String),
  releaseInfo: Schema.optional(Schema.String),
  imdbRating: Schema.optional(Schema.Union(Schema.String, Schema.Number)),
  genres: Schema.optional(StringArray),
  director: Schema.optional(StringArray),
  cast: Schema.optional(StringArray),
  links: Schema.optional(Schema.Array(MetaLink)),
  trailers: Schema.optional(Schema.Array(Trailer)),
} as const

export const MetaPreview = Schema.Struct(MetaFields)

export const Video = Schema.Struct({
  id: NonEmpty,
  title: NonEmpty,
  released: Schema.optional(Schema.String),
  thumbnail: Schema.optional(Schema.String),
  season: Schema.optional(Schema.Number.pipe(Schema.int(), Schema.nonNegative())),
  episode: Schema.optional(Schema.Number.pipe(Schema.int(), Schema.nonNegative())),
  overview: Schema.optional(Schema.String),
  available: Schema.optional(Schema.Boolean),
  streams: Schema.optional(Schema.Array(Stream)),
})

export const Meta = Schema.Struct({
  ...MetaFields,
  logo: Schema.optional(Schema.String),
  released: Schema.optional(Schema.String),
  runtime: Schema.optional(Schema.String),
  language: Schema.optional(Schema.String),
  country: Schema.optional(Schema.String),
  website: Schema.optional(Schema.String),
  videos: Schema.optional(Schema.Array(Video)),
})

export const CatalogResponse = Schema.Struct({ ...CacheHints, metas: Schema.Array(MetaPreview) })
export const MetaResponse = Schema.Struct({ ...CacheHints, meta: Meta })
export const StreamResponse = Schema.Struct({ ...CacheHints, streams: Schema.Array(Stream) })
export const SubtitlesResponse = Schema.Struct({ ...CacheHints, subtitles: Schema.Array(Subtitle) })

export const AddonCatalogItem = Schema.Struct({
  transportName: Schema.optional(Schema.String),
  transportUrl: NonEmpty,
  manifest: AddonManifest,
})

export const AddonCatalogResponse = Schema.Struct({
  ...CacheHints,
  addons: Schema.Array(AddonCatalogItem),
})
