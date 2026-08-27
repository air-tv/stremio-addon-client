package com.getair.stremio

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveAddonIntegrationTest {
    @Test
    fun loadsConfiguredMetadataStreamsAndSubtitlesWithoutLeakingUrls() = runBlocking {
        if (System.getenv("AIR_RUN_LIVE_INTEGRATION") != "true") return@runBlocking
        val metadataManifest = requireEnvironment("AIR_STREMIO_METADATA_MANIFEST")
        val streamManifest = requireEnvironment("AIR_STREMIO_STREAM_MANIFEST")
        val subtitleManifest = requireEnvironment("AIR_STREMIO_SUBTITLE_MANIFEST")
        val httpClient = HttpClient(CIO) {
            expectSuccess = false
            followRedirects = false
        }
        try {
            val transport = KtorAddonHttpTransport(httpClient)
            val options = StremioClientOptions(timeoutMillis = 30_000)
            val metadata = connectStremioAddon(metadataManifest, transport, options)
            val streams = connectStremioAddon(streamManifest, transport, options)
            val subtitles = connectStremioAddon(subtitleManifest, transport, options)

            val meta = metadata.meta("movie", TEST_ID).meta
            val streamResult = streams.streams("movie", TEST_ID)
            val subtitleResult = subtitles.subtitles("movie", TEST_ID)

            assertEqualsId(meta.id)
            assertTrue(meta.name.isNotBlank())
            assertTrue(streamResult.streams.isNotEmpty())
            assertTrue(subtitleResult.subtitles.isNotEmpty())
            assertFalse(streamManifest in streams.toString())
            assertFalse(subtitleManifest in subtitles.toString())
        } finally {
            httpClient.close()
        }
    }

    private fun assertEqualsId(actual: String) {
        assertTrue(actual == TEST_ID)
    }

    private fun requireEnvironment(name: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank)
            ?: error("Live integration configuration is incomplete")

    private companion object {
        const val TEST_ID = "tt0133093"
    }
}
