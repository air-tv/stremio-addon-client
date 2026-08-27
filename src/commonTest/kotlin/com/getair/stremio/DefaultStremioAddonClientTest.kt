package com.getair.stremio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultStremioAddonClientTest {
    private val manifest = """{
      "id":"org.example","version":"1.0.0","name":"Example",
      "resources":[{"name":"meta","types":["movie"],"idPrefixes":["tt"]},
                   {"name":"stream","types":["movie"],"idPrefixes":["tt"]}],
      "types":["movie"],"catalogs":[]
    }"""

    @Test
    fun connectsChecksCapabilitiesAndUsesDeterministicResourceUrls() = runTest {
        val requests = mutableListOf<AddonHttpRequest>()
        val transport = AddonHttpTransport { request ->
            requests += request
            val body = when {
                request.url.endsWith("/manifest.json") -> manifest
                "/meta/movie/tt0133093.json" in request.url ->
                    """{"meta":{"id":"tt0133093","type":"movie","name":"The Matrix"}}"""
                else -> """{"streams":[{"url":"https://media.invalid/movie.mkv"}]}"""
            }
            AddonHttpResponse(200, emptyMap(), body.encodeToByteArray())
        }
        val addon = connectStremioAddon("stremio://addon.invalid/base", transport)

        assertEquals("org.example", addon.manifest().id)
        assertEquals("The Matrix", addon.meta("movie", "tt0133093").meta.name)
        assertEquals(1, addon.streams("movie", "tt0133093").streams.size)
        assertTrue(requests.any { "/base/meta/movie/tt0133093.json" in it.url })
        assertTrue(requests.all { "addon.invalid" !in it.toString() })
        assertFalse("addon.invalid" in addon.toString())
        assertFailsWith<AddonResponseValidationException> { addon.subtitles("movie", "tt0133093") }
    }

    @Test
    fun transportRejectsDeclaredAndStreamingOversizeBodies() = runTest {
        val declaredClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("oversize", headers = headersOf(HttpHeaders.ContentLength, "8"))
                }
            }
        }
        try {
            assertFailsWith<AddonTransportException> {
                KtorAddonHttpTransport(declaredClient).execute(
                    AddonHttpRequest("https://example.invalid", timeoutMillis = 0, maxResponseBytes = 4),
                )
            }
        } finally {
            declaredClient.close()
        }

        val streamedClient = HttpClient(MockEngine) { engine { addHandler { respond("oversize") } } }
        try {
            assertFailsWith<AddonTransportException> {
                KtorAddonHttpTransport(streamedClient).execute(
                    AddonHttpRequest("https://example.invalid", timeoutMillis = 0, maxResponseBytes = 4),
                )
            }
        } finally {
            streamedClient.close()
        }
    }

    @Test
    fun redirectsAreBoundedRevalidatedAndDropCrossOriginHeaders() = runTest {
        val requests = mutableListOf<AddonHttpRequest>()
        val transport = AddonHttpTransport { request ->
            requests += request
            if (requests.size == 1) {
                AddonHttpResponse(
                    status = 302,
                    headers = mapOf("Location" to "https://redirected.invalid/manifest.json"),
                    body = ByteArray(0),
                )
            } else {
                AddonHttpResponse(200, emptyMap(), manifest.encodeToByteArray())
            }
        }

        connectStremioAddon(
            "https://addon.invalid/manifest.json",
            transport,
            StremioClientOptions(headers = mapOf("Authorization" to "secret")),
        )

        assertEquals("secret", requests.first().headers["Authorization"])
        assertTrue(requests[1].headers.isEmpty())
        assertTrue(requests.all { "secret" !in it.toString() })
    }
}
