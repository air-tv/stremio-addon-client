package com.getair.stremio

import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.CatalogResponse
import com.getair.stremio.model.MetaResponse
import com.getair.stremio.model.StreamResponse
import com.getair.stremio.model.SubtitlesResponse

data class StremioClientOptions(
    val urlOptions: AddonUrlOptions = AddonUrlOptions(),
    val timeoutMillis: Long = 15_000,
    val maxResponseBytes: Int = 10 * 1024 * 1024,
    val maxRedirects: Int = 5,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(timeoutMillis >= 0)
        require(maxResponseBytes > 0)
        require(maxRedirects >= 0)
    }
}

suspend fun connectStremioAddon(
    manifestUrl: String,
    transport: AddonHttpTransport,
    options: StremioClientOptions = StremioClientOptions(),
): StremioAddonClient {
    val normalizedUrl = AddonUrls.normalizeManifestUrl(manifestUrl, options.urlOptions)
    val response = execute(normalizedUrl, transport, options)
    return DefaultStremioAddonClient(
        manifestUrl = response.finalUrl,
        manifestValue = StremioJson.manifest(response.body),
        transport = transport,
        options = options,
    )
}

private class DefaultStremioAddonClient(
    private val manifestUrl: String,
    private val manifestValue: AddonManifest,
    private val transport: AddonHttpTransport,
    private val options: StremioClientOptions,
) : StremioAddonClient {
    override suspend fun manifest(options: AddonRequestOptions): AddonManifest = manifestValue

    override suspend fun catalog(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): CatalogResponse {
        requireSupported("catalog", type, id)
        return StremioJson.catalog(request("catalog", type, id, extra))
    }

    override suspend fun meta(type: String, id: String, options: AddonRequestOptions): MetaResponse {
        requireSupported("meta", type, id)
        return StremioJson.meta(request("meta", type, id))
    }

    override suspend fun streams(type: String, id: String, options: AddonRequestOptions): StreamResponse {
        requireSupported("stream", type, id)
        return StremioJson.streams(request("stream", type, id))
    }

    override suspend fun subtitles(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): SubtitlesResponse {
        requireSupported("subtitles", type, id)
        return StremioJson.subtitles(request("subtitles", type, id, extra))
    }

    private suspend fun request(
        resource: String,
        type: String,
        id: String,
        extra: Map<String, List<String>> = emptyMap(),
    ): String = execute(
        url = AddonUrls.makeResourceUrl(
            manifestUrl,
            AddonResourceRequest(resource, type, id, extra),
        ),
        transport = transport,
        options = options,
    ).body

    private fun requireSupported(resource: String, type: String, id: String) {
        if (!AddonUrls.isResourceSupported(manifestValue, resource, type, id)) {
            throw AddonResponseValidationException(resource, "Addon does not support the requested resource")
        }
    }

    override fun toString(): String =
        "DefaultStremioAddonClient(manifestUrl=<redacted>, manifest=${manifestValue.id}, transport=<redacted>)"
}

private suspend fun execute(
    url: String,
    transport: AddonHttpTransport,
    options: StremioClientOptions,
): AddonPayload {
    var currentUrl = url
    var currentHeaders = options.headers
    repeat(options.maxRedirects + 1) { redirectCount ->
        val response = transport.execute(
            AddonHttpRequest(
                url = currentUrl,
                headers = currentHeaders,
                timeoutMillis = options.timeoutMillis,
                maxResponseBytes = options.maxResponseBytes,
            ),
        )
        if (response.status in 300..399) {
            if (redirectCount >= options.maxRedirects) {
                throw AddonTransportException("Stremio addon exceeded the redirect limit")
            }
            val location = response.headers.entries
                .firstOrNull { (name, _) -> name.equals("Location", ignoreCase = true) }
                ?.value
                ?.takeIf(String::isNotBlank)
                ?: throw AddonTransportException("Stremio addon redirect is missing a destination")
            val nextUrl = AddonUrls.resolveRedirect(currentUrl, location, options.urlOptions)
            if (!AddonUrls.sameOrigin(currentUrl, nextUrl)) currentHeaders = emptyMap()
            currentUrl = nextUrl
        } else {
            if (response.status !in 200..299) {
                throw AddonTransportException("Stremio addon returned HTTP ${response.status}")
            }
            return AddonPayload(currentUrl, response.body.decodeToString())
        }
    }
    throw AddonTransportException("Stremio addon exceeded the redirect limit")
}

private data class AddonPayload(val finalUrl: String, val body: String)
