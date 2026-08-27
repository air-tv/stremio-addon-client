package com.getair.stremio.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddonBehaviorHints(
    val adult: Boolean? = null,
    val p2p: Boolean? = null,
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null,
)

@Serializable
data class CatalogExtraDefinition(
    val name: String,
    val isRequired: Boolean? = null,
    val options: List<String> = emptyList(),
    val optionsLimit: Int? = null,
)

@Serializable
data class CatalogDefinition(
    val type: String,
    val id: String,
    val name: String,
    val extra: List<CatalogExtraDefinition> = emptyList(),
)

@Serializable
data class ManifestResource(
    val name: String,
    val types: List<String>? = null,
    val idPrefixes: List<String>? = null,
)

@Serializable
data class AddonManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val resources: List<ManifestResource>,
    val types: List<String>,
    val catalogs: List<CatalogDefinition> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val logo: String? = null,
    val background: String? = null,
    val contactEmail: String? = null,
    val behaviorHints: AddonBehaviorHints? = null,
)

@Serializable
data class Subtitle(
    val id: String = "default",
    val url: String,
    val lang: String,
) {
    override fun toString(): String = "Subtitle(id=$id, url=<redacted>, lang=$lang)"
}

@Serializable
data class StreamProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "StreamProxyHeaders(request=<redacted>, response=<redacted>)"
}

@Serializable
data class StreamBehaviorHints(
    val bingeGroup: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val notWebReady: Boolean? = null,
    val countryWhitelist: List<String> = emptyList(),
    val proxyHeaders: StreamProxyHeaders? = null,
)

@Serializable
data class Stream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val sources: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val behaviorHints: StreamBehaviorHints? = null,
) {
    init {
        require(url != null || ytId != null || infoHash != null || externalUrl != null) {
            "A stream must contain url, ytId, infoHash, or externalUrl"
        }
        require(fileIdx == null || fileIdx >= 0) { "fileIdx must be non-negative" }
    }

    override fun toString(): String =
        "Stream(url=<redacted>, ytId=<redacted>, infoHash=<redacted>, fileIdx=$fileIdx, " +
            "externalUrl=<redacted>, name=$name, title=$title, description=$description, " +
            "sources=<redacted>, subtitles=$subtitles, behaviorHints=$behaviorHints)"
}

@Serializable
data class MetaLink(val name: String, val category: String, val url: String)

@Serializable
data class Trailer(val source: String, val type: String)

@Serializable
enum class PosterShape {
    @SerialName("square") Square,
    @SerialName("poster") Poster,
    @SerialName("landscape") Landscape,
}

@Serializable
data class MetaPreview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: PosterShape? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
)

@Serializable
data class Video(
    val id: String,
    val title: String,
    val released: String? = null,
    val thumbnail: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val available: Boolean? = null,
    val streams: List<Stream> = emptyList(),
)

@Serializable
data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: PosterShape? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val links: List<MetaLink> = emptyList(),
    val trailers: List<Trailer> = emptyList(),
    val logo: String? = null,
    val released: String? = null,
    val runtime: String? = null,
    val language: String? = null,
    val country: String? = null,
    val website: String? = null,
    val videos: List<Video> = emptyList(),
)

@Serializable
data class CatalogResponse(
    val metas: List<MetaPreview>,
    val cacheMaxAge: Long? = null,
    val staleRevalidate: Long? = null,
    val staleError: Long? = null,
)

@Serializable
data class MetaResponse(
    val meta: Meta,
    val cacheMaxAge: Long? = null,
    val staleRevalidate: Long? = null,
    val staleError: Long? = null,
)

@Serializable
data class StreamResponse(
    val streams: List<Stream>,
    val cacheMaxAge: Long? = null,
    val staleRevalidate: Long? = null,
    val staleError: Long? = null,
)

@Serializable
data class SubtitlesResponse(
    val subtitles: List<Subtitle>,
    val cacheMaxAge: Long? = null,
    val staleRevalidate: Long? = null,
    val staleError: Long? = null,
)
