import { Effect, Either } from "effect"
import {
  makeStremioAddonClient,
  type StremioAddonClientShape,
} from "./Client.js"
import type { AddonClientError } from "./Errors.js"
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

export interface StremioAddonError extends Error {
  readonly _tag: AddonClientError["_tag"]
  readonly url?: string
  readonly resource?: string
  readonly type?: string
  readonly id?: string
  readonly status?: number
  readonly timeoutMillis?: number
  readonly maxResponseBytes?: number
  readonly retryable?: boolean
}

export const isStremioAddonError = (value: unknown): value is StremioAddonError =>
  value instanceof Error && "_tag" in value && typeof value._tag === "string"

const publicError = (failure: AddonClientError): StremioAddonError => {
  const error = new Error(failure.message) as StremioAddonError
  error.name = failure._tag
  Object.assign(error, failure)
  return error
}

const run = async <A>(effect: Effect.Effect<A, AddonClientError>): Promise<A> => {
  const result = await Effect.runPromise(Effect.either(effect))
  if (Either.isLeft(result)) throw publicError(result.left)
  return result.right
}

/** Promise facade over the Effect-native addon client. */
export class StremioAddon {
  private constructor(private readonly client: StremioAddonClientShape) {}

  static async connect(
    input: string | URL,
    options: AddonClientOptions = {},
  ): Promise<StremioAddon> {
    return new StremioAddon(await run(makeStremioAddonClient(input, options)))
  }

  get manifest(): AddonManifest { return this.client.manifest }
  get manifestUrl(): string { return this.client.manifestUrl }

  isSupported(resource: string, type: string, id: string): boolean {
    return this.client.isSupported(resource, type, id)
  }

  request<Resource extends KnownAddonResource>(
    request: AddonRequest<Resource>,
    options?: AddonCallOptions,
  ): Promise<AddonResourceResponseMap[Resource]> {
    return run(this.client.request(request, options))
  }

  catalog(
    type: string,
    id: string,
    extra?: AddonExtra,
    options?: AddonCallOptions,
  ): Promise<CatalogResponse> {
    return run(this.client.catalog(type, id, extra, options))
  }

  meta(type: string, id: string, options?: AddonCallOptions): Promise<MetaResponse> {
    return run(this.client.meta(type, id, options))
  }

  streams(type: string, id: string, options?: AddonCallOptions): Promise<StreamResponse> {
    return run(this.client.streams(type, id, options))
  }

  subtitles(
    type: string,
    id: string,
    extra?: AddonExtra,
    options?: AddonCallOptions,
  ): Promise<SubtitlesResponse> {
    return run(this.client.subtitles(type, id, extra, options))
  }
}
