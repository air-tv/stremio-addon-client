package com.getair.stremio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorsTest {
    @Test
    fun typedFailuresPreserveCompatibilityAndRetryPolicyWithoutSensitiveContext() {
        val timeout = AddonTimeoutException(15_000)
        assertIs<AddonTransportException>(timeout)
        assertEquals(AddonFailureKind.Timeout, timeout.kind)
        assertTrue(timeout.retryable)

        listOf(408, 429, 500, 503).forEach { status ->
            assertTrue(AddonHttpStatusException(status).retryable)
        }
        listOf(400, 404).forEach { status ->
            assertFalse(AddonHttpStatusException(status).retryable)
        }

        val tooLarge = AddonResponseTooLargeException(1_024)
        assertIs<AddonTransportException>(tooLarge)
        assertEquals(AddonFailureKind.ResponseTooLarge, tooLarge.kind)
        assertFalse(tooLarge.retryable)

        val malformed = AddonInvalidJsonException("stream")
        assertIs<AddonResponseValidationException>(malformed)
        assertEquals(AddonFailureKind.InvalidJson, malformed.kind)
        assertFalse(malformed.retryable)

        val unsupported = AddonResourceUnsupportedException("subtitles")
        assertIs<AddonResponseValidationException>(unsupported)
        assertEquals(AddonFailureKind.UnsupportedResource, unsupported.kind)

        val rendered = listOf(timeout, tooLarge, malformed, unsupported).toString()
        assertFalse("https://" in rendered)
        assertFalse("Authorization" in rendered)
        assertFalse("Bearer" in rendered)
    }
}
