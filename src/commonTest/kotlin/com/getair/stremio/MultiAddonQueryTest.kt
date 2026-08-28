package com.getair.stremio

import com.getair.stremio.model.AddonCatalogResponse
import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.CatalogResponse
import com.getair.stremio.model.ManifestResource
import com.getair.stremio.model.MetaResponse
import com.getair.stremio.model.Stream
import com.getair.stremio.model.StreamResponse
import com.getair.stremio.model.SubtitlesResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiAddonQueryTest {
    @Test
    fun boundsConcurrencyAndMergesInAddonOrderUnderLoad() = runTest {
        var active = 0
        var maxActive = 0
        val addons = (0 until 200).map { index ->
            binding("addon-$index") {
                active += 1
                maxActive = maxOf(maxActive, active)
                try {
                    delay(((200 - index) % 7).toLong())
                    StreamResponse(listOf(stream("stream-$index")))
                } finally {
                    active -= 1
                }
            }
        }

        val result = queryStremioAddons(
            addons,
            StremioAddonQuery.Streams("movie", "tt0133093"),
            MultiAddonQueryOptions(
                maxConcurrency = 5,
                maxAddons = 200,
                maxItemsPerAddon = 1,
                maxTotalItems = 200,
            ),
        )

        assertEquals(5, maxActive)
        assertEquals((0 until 200).map { "addon-$it" }, result.items.map { it.addonId.value })
        assertEquals((0 until 200).map { "stream-$it" }, result.items.map { it.value.name })
        assertTrue(result.failures.isEmpty())
        assertTrue(result.unsupportedAddonIds.isEmpty())
        assertTrue(result.truncatedAddonIds.isEmpty())
    }

    @Test
    fun prefiltersCapabilitiesAndReportsSanitizedPartialFailures() = runTest {
        var unsupportedCalls = 0
        val unsupported = StremioAddonBinding(
            AddonInstanceId("unsupported"),
            object : BaseClient(manifest(idPrefixes = listOf("yt:"))) {
                override suspend fun streams(
                    type: String,
                    id: String,
                    options: AddonRequestOptions,
                ): StreamResponse {
                    unsupportedCalls += 1
                    error("must not run")
                }
            },
        )
        val invalid = binding("invalid") {
            throw AddonResponseValidationException(
                "stream",
                "https://configured.invalid/secret?token=do-not-leak",
            )
        }
        val unexpected = binding("unexpected") {
            error("Authorization: Bearer do-not-leak")
        }
        val successful = binding("successful") {
            StreamResponse(listOf(stream("usable")))
        }

        val result = queryStremioAddons(
            listOf(unsupported, invalid, unexpected, successful),
            StremioAddonQuery.Streams("movie", "tt0133093"),
        )

        assertEquals(0, unsupportedCalls)
        assertEquals(listOf("unsupported"), result.unsupportedAddonIds.map { it.value })
        assertEquals(
            listOf(AddonQueryFailureKind.InvalidResponse, AddonQueryFailureKind.Unexpected),
            result.failures.map { it.kind },
        )
        assertEquals(listOf("successful"), result.items.map { it.addonId.value })
        assertFalse("configured.invalid" in result.toString())
        assertFalse("do-not-leak" in result.toString())
        assertFalse("Authorization" in result.toString())
    }

    @Test
    fun ownedTimeoutsAndStatusFailuresRemainIsolatedAndActionable() = runTest {
        val timedHttp = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/manifest.json")) {
                        respond(MULTI_ADDON_MANIFEST)
                    } else {
                        awaitCancellation()
                    }
                }
            }
        }
        try {
            val timedTransport = AddonHttpTransport { request ->
                if (request.url.endsWith("/manifest.json")) {
                    AddonHttpResponse(200, emptyMap(), MULTI_ADDON_MANIFEST.encodeToByteArray())
                } else {
                    KtorAddonHttpTransport(timedHttp).execute(request.copy(timeoutMillis = 10))
                }
            }
            val timedClient = connectStremioAddon(
                "https://timed.invalid/manifest.json",
                timedTransport,
                StremioClientOptions(timeoutMillis = 0),
            )
            val timedOut = StremioAddonBinding(AddonInstanceId("timed-out"), timedClient)
            val unavailable = binding("unavailable") {
                throw AddonHttpStatusException(503)
            }
            val missing = binding("missing") {
                throw AddonHttpStatusException(404)
            }
            val tooLarge = binding("too-large") {
                throw AddonResponseTooLargeException(1_024)
            }
            val successful = binding("successful") {
                StreamResponse(listOf(stream("usable")))
            }

            val result = queryStremioAddons(
                listOf(timedOut, unavailable, missing, tooLarge, successful),
                StremioAddonQuery.Streams("movie", "tt0133093"),
                MultiAddonQueryOptions(maxConcurrency = 5),
            )

            assertEquals(listOf("successful"), result.items.map { it.addonId.value })
            assertEquals(
                listOf(
                    AddonQueryFailureKind.Timeout,
                    AddonQueryFailureKind.HttpStatus,
                    AddonQueryFailureKind.HttpStatus,
                    AddonQueryFailureKind.ResponseTooLarge,
                ),
                result.failures.map { it.kind },
            )
            assertEquals(listOf(true, true, false, false), result.failures.map { it.retryable })
            assertEquals(listOf(null, 503, 404, null), result.failures.map { it.httpStatus })
            assertFalse("timed.invalid" in result.toString())
            assertFalse("1024" in result.toString())
        } finally {
            timedHttp.close()
        }
    }

    @Test
    fun enforcesPerAddonAndAggregateResultLimitsDeterministically() = runTest {
        val addons = (0 until 3).map { addonIndex ->
            binding("addon-$addonIndex") {
                StreamResponse((0 until 5).map { stream("$addonIndex-$it") })
            }
        }

        val result = queryStremioAddons(
            addons,
            StremioAddonQuery.Streams("movie", "tt0133093"),
            MultiAddonQueryOptions(
                maxConcurrency = 3,
                maxAddons = 3,
                maxItemsPerAddon = 3,
                maxTotalItems = 5,
            ),
        )

        assertEquals(listOf("0-0", "0-1", "0-2", "1-0", "1-1"), result.items.map { it.value.name })
        assertEquals(listOf("addon-0", "addon-1", "addon-2"), result.truncatedAddonIds.map { it.value })
    }

    @Test
    fun callerCancellationStopsEveryWorkerAndDoesNotStartQueuedAddons() = runTest {
        var active = 0
        var started = 0
        val bothStarted = CompletableDeferred<Unit>()
        val addons = (0 until 100).map { index ->
            binding("addon-$index") {
                started += 1
                active += 1
                if (active == 2) bothStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active -= 1
                }
            }
        }

        val queryJob = launch {
            queryStremioAddons(
                addons,
                StremioAddonQuery.Streams("movie", "tt0133093"),
                MultiAddonQueryOptions(maxConcurrency = 2, maxAddons = 100),
            )
        }
        bothStarted.await()
        assertEquals(2, active)

        queryJob.cancelAndJoin()

        assertEquals(0, active)
        assertEquals(2, started)
    }

    @Test
    fun rejectsUnsafeOrAmbiguousIdentityAndAddonCountsBeforeWork() = runTest {
        assertFailsWith<IllegalArgumentException> {
            AddonInstanceId("https://addon.invalid/secret")
        }
        val duplicate = binding("same") { StreamResponse(listOf(stream("unused"))) }
        assertFailsWith<IllegalArgumentException> {
            queryStremioAddons(
                listOf(duplicate, duplicate),
                StremioAddonQuery.Streams("movie", "tt0133093"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            queryStremioAddons(
                listOf(duplicate, binding("other") { StreamResponse(listOf(stream("unused"))) }),
                StremioAddonQuery.Streams("movie", "tt0133093"),
                MultiAddonQueryOptions(maxAddons = 1),
            )
        }
    }

    private fun binding(
        id: String,
        streams: suspend () -> StreamResponse,
    ): StremioAddonBinding = StremioAddonBinding(
        AddonInstanceId(id),
        object : BaseClient(manifest()) {
            override suspend fun streams(
                type: String,
                id: String,
                options: AddonRequestOptions,
            ): StreamResponse = streams()
        },
    )

    private fun manifest(idPrefixes: List<String> = listOf("tt")) = AddonManifest(
        id = "org.example",
        version = "1.0.0",
        name = "Example",
        resources = listOf(ManifestResource("stream", listOf("movie"), idPrefixes)),
        types = listOf("movie"),
        idPrefixes = idPrefixes,
    )

    private fun stream(name: String) = Stream(url = "https://media.invalid/$name.mkv", name = name)
}

private const val MULTI_ADDON_MANIFEST = """{
  "id":"org.example","version":"1.0.0","name":"Example",
  "resources":[{"name":"stream","types":["movie"],"idPrefixes":["tt"]}],
  "types":["movie"],"catalogs":[]
}"""

private open class BaseClient(private val manifest: AddonManifest) : StremioAddonClient {
    override suspend fun manifest(options: AddonRequestOptions): AddonManifest = manifest

    override suspend fun catalog(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): CatalogResponse = error("unexpected catalog request")

    override suspend fun meta(type: String, id: String, options: AddonRequestOptions): MetaResponse =
        error("unexpected meta request")

    override suspend fun streams(type: String, id: String, options: AddonRequestOptions): StreamResponse =
        error("unexpected stream request")

    override suspend fun subtitles(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): SubtitlesResponse = error("unexpected subtitles request")

    override suspend fun addonCatalog(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): AddonCatalogResponse = error("unexpected addon catalog request")
}
