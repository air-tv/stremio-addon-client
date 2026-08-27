package com.getair.stremio

import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.AddonCatalogResponse
import com.getair.stremio.model.CatalogResponse
import com.getair.stremio.model.MetaResponse
import com.getair.stremio.model.StreamResponse
import com.getair.stremio.model.SubtitlesResponse
import kotlinx.datetime.Clock

data class StremioClientOptions(
    val urlOptions: AddonUrlOptions = AddonUrlOptions(),
    val timeoutMillis: Long = 15_000,
    val maxResponseBytes: Int = 10 * 1024 * 1024,
    val maxRedirects: Int = 5,
    val headers: Map<String, String> = emptyMap(),
    val responseCache: AddonResponseCache? = null,
    val defaultCacheTtlMillis: Long = 5 * 60 * 1_000,
) {
    init {
        require(timeoutMillis >= 0)
        require(maxResponseBytes > 0)
        require(maxRedirects >= 0)
        require(defaultCacheTtlMillis >= 0)
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
        return request("catalog", type, id, extra, options, StremioJson::catalog) { it.cacheMaxAge }
    }

    override suspend fun meta(type: String, id: String, options: AddonRequestOptions): MetaResponse {
        requireSupported("meta", type, id)
        return request("meta", type, id, callOptions = options, decode = StremioJson::meta) { it.cacheMaxAge }
    }

    override suspend fun streams(type: String, id: String, options: AddonRequestOptions): StreamResponse {
        requireSupported("stream", type, id)
        return request("stream", type, id, callOptions = options, decode = StremioJson::streams) { it.cacheMaxAge }
    }

    override suspend fun subtitles(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): SubtitlesResponse {
        requireSupported("subtitles", type, id)
        return request("subtitles", type, id, extra, options, StremioJson::subtitles) { it.cacheMaxAge }
    }

    override suspend fun addonCatalog(
        type: String,
        id: String,
        extra: Map<String, List<String>>,
        options: AddonRequestOptions,
    ): AddonCatalogResponse {
        requireSupported("addon_catalog", type, id)
        return request("addon_catalog", type, id, extra, options, StremioJson::addonCatalog) { it.cacheMaxAge }
    }

    private suspend fun <T> request(
        resource: String,
        type: String,
        id: String,
        extra: Map<String, List<String>> = emptyMap(),
        callOptions: AddonRequestOptions = AddonRequestOptions(),
        decode: (String) -> T,
        cacheMaxAgeSeconds: (T) -> Long?,
    ): T {
        val url = AddonUrls.makeResourceUrl(
            manifestUrl,
            AddonResourceRequest(resource, type, id, extra),
        )
        return executeResource(url, callOptions, decode, cacheMaxAgeSeconds)
    }

    private suspend fun <T> executeResource(
        url: String,
        callOptions: AddonRequestOptions,
        decode: (String) -> T,
        cacheMaxAgeSeconds: (T) -> Long?,
    ): T {
        val cache = options.responseCache
        val key = if (cache == null) null else {
            "@get-air/stremio:${secureAddonCacheKey(url)}"
        }
        if (key != null && cache != null && !callOptions.bypassCache) {
            val now = Clock.System.now().toEpochMilliseconds()
            val cached = runCatching { cache.get(key) }.getOrNull()
            if (cached != null && (cached.expiresAtEpochMillis == null || cached.expiresAtEpochMillis > now)) {
                return decode(cached.value)
            }
            if (cached != null) runCatching { cache.remove(key) }
        }
        val payload = execute(url, transport, options)
        val value = decode(payload.body)
        val cacheControl = payload.headers.header("Cache-Control")
        val directives = cacheControl?.split(',')?.map(String::trim).orEmpty()
        val forbidden = directives.any {
            it.equals("no-store", ignoreCase = true) || it.startsWith("private", ignoreCase = true)
        }
        if (key != null && cache != null && !forbidden) {
            val headerMaxAge = cacheControl?.let(MAX_AGE::find)
            val bodyMaxAge = cacheMaxAgeSeconds(value)
            val ttl = when {
                headerMaxAge != null -> headerMaxAge.groupValues[1].toLongOrNull()?.toPositiveMillis()
                bodyMaxAge != null -> bodyMaxAge.toPositiveMillis()
                else -> options.defaultCacheTtlMillis.takeIf { it > 0 }
            }
            if (ttl != null) {
                val now = Clock.System.now().toEpochMilliseconds()
                val expires = if (ttl > Long.MAX_VALUE - now) Long.MAX_VALUE else now + ttl
                runCatching { cache.set(key, AddonCacheEntry(payload.body, expires)) }
            }
        }
        return value
    }

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
            return AddonPayload(currentUrl, response.body.decodeToString(), response.headers)
        }
    }
    throw AddonTransportException("Stremio addon exceeded the redirect limit")
}

private data class AddonPayload(
    val finalUrl: String,
    val body: String,
    val headers: Map<String, String>,
)

private fun Map<String, String>.header(name: String): String? =
    entries.firstOrNull { (candidate, _) -> candidate.equals(name, ignoreCase = true) }?.value

private val MAX_AGE = Regex("(?:^|,)\\s*max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)

private fun Long.toPositiveMillis(): Long? = when {
    this <= 0 -> null
    this > Long.MAX_VALUE / 1_000 -> Long.MAX_VALUE
    else -> this * 1_000
}
