package com.getair.stremio

/** Stable, redacted failure categories for request policy and retry decisions. */
enum class AddonFailureKind {
    InvalidUrl,
    Transport,
    Timeout,
    HttpStatus,
    ResponseTooLarge,
    InvalidJson,
    InvalidResponse,
    UnsupportedResource,
}

/**
 * Implemented by every expected addon-client failure.
 *
 * Implementations deliberately omit addon URLs, headers, IDs, and response bodies.
 */
sealed interface AddonClientFailure {
    val kind: AddonFailureKind
    val retryable: Boolean
}

class InvalidAddonUrlException(message: String) :
    IllegalArgumentException(message),
    AddonClientFailure {
    override val kind: AddonFailureKind = AddonFailureKind.InvalidUrl
    override val retryable: Boolean = false
}

open class AddonTransportException(
    message: String,
    override val retryable: Boolean = true,
) : IllegalStateException(message), AddonClientFailure {
    open override val kind: AddonFailureKind = AddonFailureKind.Transport
}

class AddonTimeoutException(
    val timeoutMillis: Long,
) : AddonTransportException(
    message = "Stremio addon request timed out after ${timeoutMillis}ms",
    retryable = true,
) {
    override val kind: AddonFailureKind = AddonFailureKind.Timeout
}

class AddonHttpStatusException(
    val status: Int,
) : AddonTransportException(
    message = "Stremio addon returned HTTP $status",
    retryable = status == 408 || status == 429 || status >= 500,
) {
    override val kind: AddonFailureKind = AddonFailureKind.HttpStatus
}

class AddonResponseTooLargeException(
    val maxResponseBytes: Int,
) : AddonTransportException(
    message = "Stremio addon response exceeded the configured size limit",
    retryable = false,
) {
    override val kind: AddonFailureKind = AddonFailureKind.ResponseTooLarge
}

open class AddonResponseValidationException(
    val resource: String,
    message: String,
) : IllegalArgumentException(message), AddonClientFailure {
    open override val kind: AddonFailureKind = AddonFailureKind.InvalidResponse
    override val retryable: Boolean = false
}

class AddonInvalidJsonException(resource: String) : AddonResponseValidationException(
    resource = resource,
    message = "Addon response is not valid JSON",
) {
    override val kind: AddonFailureKind = AddonFailureKind.InvalidJson
}

class AddonResourceUnsupportedException(resource: String) : AddonResponseValidationException(
    resource = resource,
    message = "Addon does not support the requested resource",
) {
    override val kind: AddonFailureKind = AddonFailureKind.UnsupportedResource
}
