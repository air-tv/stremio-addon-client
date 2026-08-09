export {
  StremioAddon,
  isResourceSupported,
  makeResourceUrl,
  makeStremioAddonClient,
  normalizeManifestUrl,
  type StremioAddonClientShape,
} from "./Client.js"
export * from "./Errors.js"
export {
  AddonCatalogResponse as AddonCatalogResponseSchema,
  AddonManifest as AddonManifestSchema,
  CatalogDefinition as CatalogDefinitionSchema,
  CatalogExtraDefinition as CatalogExtraDefinitionSchema,
  CatalogResponse as CatalogResponseSchema,
  ManifestResource as ManifestResourceSchema,
  ManifestResourceDefinition as ManifestResourceDefinitionSchema,
  Meta as MetaSchema,
  MetaPreview as MetaPreviewSchema,
  MetaResponse as MetaResponseSchema,
  Stream as StreamSchema,
  StreamResponse as StreamResponseSchema,
  Subtitle as SubtitleSchema,
  SubtitlesResponse as SubtitlesResponseSchema,
} from "./Schemas.js"
export type * from "./Types.js"
