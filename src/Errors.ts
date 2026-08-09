import { Schema } from "effect"

export class InvalidAddonUrlError extends Schema.TaggedError<InvalidAddonUrlError>()(
  "InvalidAddonUrlError",
  { url: Schema.String, message: Schema.String },
) {}

export class AddonTransportError extends Schema.TaggedError<AddonTransportError>()(
  "AddonTransportError",
  { url: Schema.String, message: Schema.String, retryable: Schema.Boolean },
) {}

export class AddonTimeoutError extends Schema.TaggedError<AddonTimeoutError>()(
  "AddonTimeoutError",
  { url: Schema.String, timeoutMillis: Schema.Number, message: Schema.String },
) {}

export class AddonHttpStatusError extends Schema.TaggedError<AddonHttpStatusError>()(
  "AddonHttpStatusError",
  { url: Schema.String, status: Schema.Number, message: Schema.String, retryable: Schema.Boolean },
) {}

export class AddonResponseTooLargeError extends Schema.TaggedError<AddonResponseTooLargeError>()(
  "AddonResponseTooLargeError",
  { url: Schema.String, maxResponseBytes: Schema.Number, message: Schema.String },
) {}

export class AddonInvalidJsonError extends Schema.TaggedError<AddonInvalidJsonError>()(
  "AddonInvalidJsonError",
  { url: Schema.String, message: Schema.String },
) {}

export class AddonResponseValidationError extends Schema.TaggedError<AddonResponseValidationError>()(
  "AddonResponseValidationError",
  { url: Schema.String, resource: Schema.String, message: Schema.String },
) {}

export class AddonResourceUnsupportedError extends Schema.TaggedError<AddonResourceUnsupportedError>()(
  "AddonResourceUnsupportedError",
  { resource: Schema.String, type: Schema.String, id: Schema.String, message: Schema.String },
) {}

export class AddonCacheReadError extends Schema.TaggedError<AddonCacheReadError>()(
  "AddonCacheReadError",
  { key: Schema.String, message: Schema.String },
) {}

export class AddonCacheWriteError extends Schema.TaggedError<AddonCacheWriteError>()(
  "AddonCacheWriteError",
  { key: Schema.String, message: Schema.String },
) {}

export class AddonCacheKeyError extends Schema.TaggedError<AddonCacheKeyError>()(
  "AddonCacheKeyError",
  { url: Schema.String, message: Schema.String },
) {}

export type AddonClientError =
  | InvalidAddonUrlError
  | AddonTransportError
  | AddonTimeoutError
  | AddonHttpStatusError
  | AddonResponseTooLargeError
  | AddonInvalidJsonError
  | AddonResponseValidationError
  | AddonResourceUnsupportedError
  | AddonCacheKeyError
