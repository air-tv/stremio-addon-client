import { Effect } from "effect"
import {
  type AddonClientError,
  AddonResourceUnsupportedError,
  InvalidAddonUrlError,
} from "./Errors.js"
import { AddonManifest as AddonManifestSchema } from "./Schemas.js"
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
import { fetchDecoded, schemaFor } from "./internal/Request.js"
import {
  isResourceSupported,
  makeResourceUrl,
  normalizeOptions,
  validateUrl,
} from "./internal/Url.js"

export {
  isResourceSupported,
  makeResourceUrl,
  normalizeManifestUrl,
} from "./internal/Url.js"

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
  const manifest = yield* fetchDecoded(
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
      return yield* new AddonResourceUnsupportedError({
        resource: addonRequest.resource,
        type: addonRequest.type,
        id: addonRequest.id,
        message: `Addon does not support ${addonRequest.resource}/${addonRequest.type}/${addonRequest.id}`,
      })
    }
    return yield* fetchDecoded(
      new URL(makeResourceUrl(manifestUrl, addonRequest)),
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

export interface StremioAddon extends StremioAddonClientShape {}
export class StremioAddon {
  private constructor(client: StremioAddonClientShape) {
    Object.assign(this, client)
  }

  static readonly connect = Effect.fn("StremioAddon.connect")(function* (
    input: string | URL,
    options: AddonClientOptions = {},
  ) {
    return new StremioAddon(yield* makeStremioAddonClient(input, options))
  })
}
