package com.getair.stremio

import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.CatalogDefinition
import com.getair.stremio.model.ManifestResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddonUrlsTest {
    @Test
    fun normalizesBaseAndInstallUrls() {
        assertEquals(
            "https://addon.example/base/manifest.json",
            AddonUrls.normalizeManifestUrl("stremio://addon.example/base#install"),
        )
        assertEquals(
            "https://addon.example/manifest.json?token=test",
            AddonUrls.normalizeManifestUrl("https://addon.example?token=test"),
        )
    }

    @Test
    fun rejectsUnsafeHostsAndCredentials() {
        listOf(
            "http://addon.example/manifest.json",
            "https://127.0.0.1/manifest.json",
            "https://[::1]/manifest.json",
            "https://[::ffff:7f00:1]/manifest.json",
            "https://user:secret@addon.example/manifest.json",
        ).forEach { url ->
            val failure = assertFailsWith<InvalidAddonUrlException> {
                AddonUrls.normalizeManifestUrl(url)
            }
            assertEquals(AddonFailureKind.InvalidUrl, failure.kind)
            assertFalse(failure.retryable)
        }
        assertEquals(
            "http://localhost/manifest.json",
            AddonUrls.normalizeManifestUrl(
                "http://localhost",
                AddonUrlOptions(allowHttp = true, allowPrivateNetwork = true),
            ),
        )
    }

    @Test
    fun encodesComponentsAndDeterministicRepeatedExtras() {
        assertEquals(
            "https://addon.example/catalog/movie/popular%20films/genre=Drama&genre=Sci+Fi&skip=20.json",
            AddonUrls.makeResourceUrl(
                "https://addon.example/manifest.json",
                AddonResourceRequest(
                    resource = "catalog",
                    type = "movie",
                    id = "popular films",
                    extra = mapOf("skip" to listOf("20"), "genre" to listOf("Drama", "Sci Fi")),
                ),
            ),
        )
    }

    @Test
    fun checksResourceTypesAndIdPrefixes() {
        val manifest = AddonManifest(
            id = "org.example",
            version = "1.0.0",
            name = "Example",
            resources = listOf(
                ManifestResource("catalog"),
                ManifestResource("stream", listOf("movie", "series"), listOf("tt")),
                ManifestResource("meta", listOf("movie")),
                ManifestResource("subtitles"),
            ),
            types = listOf("movie", "series"),
            catalogs = listOf(CatalogDefinition("movie", "popular", "Popular")),
            idPrefixes = listOf("tt"),
        )

        assertTrue(AddonUrls.isResourceSupported(manifest, "catalog", "movie", "popular"))
        assertTrue(AddonUrls.isResourceSupported(manifest, "stream", "movie", "tt1254207"))
        assertFalse(AddonUrls.isResourceSupported(manifest, "stream", "movie", "kitsu:1"))
        assertTrue(AddonUrls.isResourceSupported(manifest, "meta", "movie", "kitsu:1"))
        assertTrue(AddonUrls.isResourceSupported(manifest, "subtitles", "movie", "tt1254207"))
        assertFalse(AddonUrls.isResourceSupported(manifest, "subtitles", "movie", "kitsu:1"))
    }

    @Test
    fun resolvesAndRevalidatesRelativeRedirects() {
        assertEquals(
            "https://addon.example/addons/manifest.json",
            AddonUrls.resolveRedirect(
                "https://addon.example/base/manifest.json",
                "../../addons/manifest.json#ignored",
            ),
        )
        assertFailsWith<InvalidAddonUrlException> {
            AddonUrls.resolveRedirect("https://addon.example/manifest.json", "http://localhost/result.json")
        }
    }
}
