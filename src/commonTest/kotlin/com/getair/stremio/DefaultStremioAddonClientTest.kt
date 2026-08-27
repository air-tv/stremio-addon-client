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
                   {"name":"stream","types":["movie"],"idPrefixes":["tt"]},
                   {"name":"addon_catalog","types":["addon"],"idPrefixes":["community"]}],
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
                "/addon_catalog/addon/community.json" in request.url ->
                    """{"addons":[{"transportUrl":"https://entry.invalid/manifest.json","manifest":{"id":"org.entry","version":"1.0.0","name":"Entry","resources":["stream"],"types":["movie"]}}]}"""
                else -> """{"streams":[{"url":"https://media.invalid/movie.mkv"}]}"""
            }
            AddonHttpResponse(200, emptyMap(), body.encodeToByteArray())
        }
        val addon = connectStremioAddon("stremio://addon.invalid/base", transport)

        assertEquals("org.example", addon.manifest().id)
        assertEquals("The Matrix", addon.meta("movie", "tt0133093").meta.name)
        assertEquals(1, addon.streams("movie", "tt0133093").streams.size)
        assertEquals("org.entry", addon.addonCatalog("addon", "community").addons.single().manifest.id)
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

    @Test
    fun resourceCacheHonorsMaxAgeNoStoreBypassAndSafeKeys() = runTest {
        var resourceRequests = 0
        val cache = MemoryAddonResponseCache()
        var noStore = false
        val transport = AddonHttpTransport { request ->
            if (request.url.endsWith("/manifest.json")) {
                AddonHttpResponse(200, emptyMap(), manifest.encodeToByteArray())
            } else {
                resourceRequests += 1
                AddonHttpResponse(
                    status = 200,
                    headers = mapOf("Cache-Control" to if (noStore) "no-store" else "public, max-age=60"),
                    body = """{"streams":[{"url":"https://media.invalid/movie.mkv"}]}""".encodeToByteArray(),
                )
            }
        }
        val addon = connectStremioAddon(
            "https://addon.invalid/config-secret/manifest.json",
            transport,
            StremioClientOptions(responseCache = cache),
        )

        addon.streams("movie", "tt0133093")
        addon.streams("movie", "tt0133093")
        assertEquals(1, resourceRequests)
        addon.streams("movie", "tt0133093", AddonRequestOptions(bypassCache = true))
        assertEquals(2, resourceRequests)
        assertTrue(cache.keys().all { "addon.invalid" !in it && "config-secret" !in it })
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            secureAddonCacheKey("abc"),
        )

        val noStoreAddon = connectStremioAddon(
            "https://other.invalid/manifest.json",
            transport,
            StremioClientOptions(responseCache = MemoryAddonResponseCache()),
        )
        noStore = true
        noStoreAddon.streams("movie", "tt0133093")
        noStoreAddon.streams("movie", "tt0133093")
        assertEquals(4, resourceRequests)

        val failingCache = object : AddonResponseCache {
            override suspend fun get(key: String): AddonCacheEntry? = error("read failure")
            override suspend fun set(key: String, entry: AddonCacheEntry) = error("write failure")
            override suspend fun remove(key: String) = Unit
        }
        val failOpen = connectStremioAddon(
            "https://fail-open.invalid/manifest.json",
            transport,
            StremioClientOptions(responseCache = failingCache),
        )
        assertEquals(1, failOpen.streams("movie", "tt0133093").streams.size)
    }

    @Test
    fun resourceCacheUsesValidatedBodyTtlAndRejectsPrivateResponses() = runTest {
        var resourceRequests = 0
        var invalid = true
        var privateResponse = false
        var bodyCacheMaxAge = 60
        val transport = AddonHttpTransport { request ->
            if (request.url.endsWith("/manifest.json")) {
                AddonHttpResponse(200, emptyMap(), manifest.encodeToByteArray())
            } else {
                resourceRequests += 1
                val body = if (invalid) {
                    invalid = false
                    """{"streams":"invalid","cacheMaxAge":60}"""
                } else {
                    """{"streams":[{"url":"https://media.invalid/movie.mkv"}],"cacheMaxAge":$bodyCacheMaxAge}"""
                }
                AddonHttpResponse(
                    status = 200,
                    headers = if (privateResponse) mapOf("Cache-Control" to "private") else emptyMap(),
                    body = body.encodeToByteArray(),
                )
            }
        }
        val addon = connectStremioAddon(
            "https://body-ttl.invalid/manifest.json",
            transport,
            StremioClientOptions(
                responseCache = MemoryAddonResponseCache(),
                defaultCacheTtlMillis = 0,
            ),
        )

        assertFailsWith<AddonResponseValidationException> { addon.streams("movie", "tt0133093") }
        addon.streams("movie", "tt0133093")
        addon.streams("movie", "tt0133093")
        assertEquals(2, resourceRequests)

        privateResponse = true
        val privateAddon = connectStremioAddon(
            "https://private.invalid/manifest.json",
            transport,
            StremioClientOptions(responseCache = MemoryAddonResponseCache()),
        )
        privateAddon.streams("movie", "tt0133093")
        privateAddon.streams("movie", "tt0133093")
        assertEquals(4, resourceRequests)

        privateResponse = false
        bodyCacheMaxAge = 0
        val zeroTtlAddon = connectStremioAddon(
            "https://zero-ttl.invalid/manifest.json",
            transport,
            StremioClientOptions(responseCache = MemoryAddonResponseCache()),
        )
        zeroTtlAddon.streams("movie", "tt0133093")
        zeroTtlAddon.streams("movie", "tt0133093")
        assertEquals(6, resourceRequests)
    }
}
