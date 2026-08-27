package com.getair.stremio

import com.getair.stremio.model.AddonBehaviorHints
import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.CatalogDefinition
import com.getair.stremio.model.CatalogExtraDefinition
import com.getair.stremio.model.CatalogResponse
import com.getair.stremio.model.ManifestResource
import com.getair.stremio.model.Meta
import com.getair.stremio.model.MetaLink
import com.getair.stremio.model.MetaPreview
import com.getair.stremio.model.MetaResponse
import com.getair.stremio.model.PosterShape
import com.getair.stremio.model.Stream
import com.getair.stremio.model.StreamBehaviorHints
import com.getair.stremio.model.StreamProxyHeaders
import com.getair.stremio.model.StreamResponse
import com.getair.stremio.model.Subtitle
import com.getair.stremio.model.SubtitlesResponse
import com.getair.stremio.model.Trailer
import com.getair.stremio.model.Video
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AddonResponseValidationException(
    val resource: String,
    message: String,
) : IllegalArgumentException(message)

object StremioJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun manifest(input: String): AddonManifest {
        val root = parse(input, "manifest").record("manifest")
        val resources = root["resources"].array("manifest resources").map { resource ->
            when (resource) {
                is JsonPrimitive -> ManifestResource(resource.requiredString("resource name"))
                is JsonObject -> ManifestResource(
                    name = resource["name"].requiredString("resource name"),
                    types = resource["types"].stringArray(),
                    idPrefixes = resource["idPrefixes"].optionalStringArray(),
                )
                else -> throw AddonResponseValidationException("manifest", "Manifest resource is invalid")
            }
        }
        if (resources.isEmpty()) throw AddonResponseValidationException("manifest", "Manifest resources are empty")
        val types = root["types"].stringArray()
        if (types.isEmpty()) throw AddonResponseValidationException("manifest", "Manifest types are empty")
        return AddonManifest(
            id = root["id"].requiredString("manifest id"),
            version = root["version"].requiredString("manifest version"),
            name = root["name"].requiredString("manifest name"),
            description = root["description"].optionalString().orEmpty(),
            resources = resources,
            types = types,
            catalogs = root["catalogs"].optionalArray().map { catalogElement ->
                val catalog = catalogElement.record("catalog definition")
                CatalogDefinition(
                    type = catalog["type"].requiredString("catalog type"),
                    id = catalog["id"].requiredString("catalog id"),
                    name = catalog["name"].requiredString("catalog name"),
                    extra = catalog["extra"].optionalArray().map { extraElement ->
                        val extra = extraElement.record("catalog extra")
                        CatalogExtraDefinition(
                            name = extra["name"].requiredString("extra name"),
                            isRequired = extra["isRequired"].optionalBoolean(),
                            options = extra["options"].optionalStringArray().orEmpty(),
                            optionsLimit = extra["optionsLimit"].nonNegativeInt(),
                        )
                    },
                )
            },
            idPrefixes = root["idPrefixes"].optionalStringArray().orEmpty(),
            logo = root["logo"].optionalString(),
            background = root["background"].optionalString(),
            contactEmail = root["contactEmail"].optionalString(),
            behaviorHints = (root["behaviorHints"] as? JsonObject)?.let { hints ->
                AddonBehaviorHints(
                    adult = hints["adult"].optionalBoolean(),
                    p2p = hints["p2p"].optionalBoolean(),
                    configurable = hints["configurable"].optionalBoolean(),
                    configurationRequired = hints["configurationRequired"].optionalBoolean(),
                )
            },
        )
    }

    fun catalog(input: String): CatalogResponse {
        val root = parse(input, "catalog").record("catalog")
        return CatalogResponse(
            metas = root["metas"].array("catalog metas").map { preview(it.record("meta preview")) },
            cacheMaxAge = root.cacheLong("cacheMaxAge"),
            staleRevalidate = root.cacheLong("staleRevalidate"),
            staleError = root.cacheLong("staleError"),
        )
    }

    fun meta(input: String): MetaResponse {
        val root = parse(input, "meta").record("meta response")
        return MetaResponse(
            meta = meta(root["meta"].record("meta")),
            cacheMaxAge = root.cacheLong("cacheMaxAge"),
            staleRevalidate = root.cacheLong("staleRevalidate"),
            staleError = root.cacheLong("staleError"),
        )
    }

    fun streams(input: String): StreamResponse {
        val root = parse(input, "stream").record("stream response")
        return StreamResponse(
            streams = root["streams"].array("streams").map { stream(it.record("stream")) },
            cacheMaxAge = root.cacheLong("cacheMaxAge"),
            staleRevalidate = root.cacheLong("staleRevalidate"),
            staleError = root.cacheLong("staleError"),
        )
    }

    fun subtitles(input: String): SubtitlesResponse {
        val root = parse(input, "subtitles").record("subtitle response")
        return SubtitlesResponse(
            subtitles = root["subtitles"].array("subtitles").map { subtitle(it.record("subtitle")) },
            cacheMaxAge = root.cacheLong("cacheMaxAge"),
            staleRevalidate = root.cacheLong("staleRevalidate"),
            staleError = root.cacheLong("staleError"),
        )
    }

    private fun preview(root: JsonObject): MetaPreview = MetaPreview(
        id = root["id"].requiredString("meta id"),
        type = root["type"].requiredString("meta type"),
        name = root["name"].requiredString("meta name"),
        poster = root["poster"].optionalString(),
        posterShape = root["posterShape"].posterShape(),
        background = root["background"].optionalString(),
        description = root["description"].optionalString(),
        releaseInfo = root["releaseInfo"].optionalString(),
        imdbRating = root["imdbRating"].optionalString(),
        genres = root["genres"].optionalStringArray().orEmpty(),
        director = root["director"].optionalStringArray().orEmpty(),
        cast = root["cast"].optionalStringArray().orEmpty(),
        links = root.links(),
        trailers = root.trailers(),
    )

    private fun meta(root: JsonObject): Meta = Meta(
        id = root["id"].requiredString("meta id"),
        type = root["type"].requiredString("meta type"),
        name = root["name"].requiredString("meta name"),
        poster = root["poster"].optionalString(),
        posterShape = root["posterShape"].posterShape(),
        background = root["background"].optionalString(),
        description = root["description"].optionalString(),
        releaseInfo = root["releaseInfo"].optionalString(),
        imdbRating = root["imdbRating"].optionalString(),
        genres = root["genres"].optionalStringArray().orEmpty(),
        director = root["director"].optionalStringArray().orEmpty(),
        cast = root["cast"].optionalStringArray().orEmpty(),
        links = root.links(),
        trailers = root.trailers(),
        logo = root["logo"].optionalString(),
        released = root["released"].optionalString(),
        runtime = root["runtime"].optionalString(),
        language = root["language"].optionalString(),
        country = root["country"].optionalString(),
        website = root["website"].optionalString(),
        videos = root["videos"].optionalArray().map { video(it.record("video")) },
    )

    private fun video(root: JsonObject): Video = Video(
        id = root["id"].requiredString("video id"),
        title = root["title"].requiredString("video title"),
        released = root["released"].optionalString(),
        thumbnail = root["thumbnail"].optionalString(),
        season = root["season"].nonNegativeInt(),
        episode = root["episode"].nonNegativeInt(),
        overview = root["overview"].optionalString(),
        available = root["available"].optionalBoolean(),
        streams = root["streams"].optionalArray().map { stream(it.record("video stream")) },
    )

    private fun stream(root: JsonObject): Stream {
        val behavior = (root["behaviorHints"] as? JsonObject)?.let { hints ->
            StreamBehaviorHints(
                bingeGroup = hints["bingeGroup"].optionalString(),
                videoHash = hints["videoHash"].optionalString(),
                videoSize = hints["videoSize"].nonNegativeLong(),
                filename = hints["filename"].optionalString(),
                notWebReady = hints["notWebReady"].optionalBoolean(),
                countryWhitelist = hints["countryWhitelist"].optionalStringArray().orEmpty(),
                proxyHeaders = (hints["proxyHeaders"] as? JsonObject)?.let { proxy ->
                    StreamProxyHeaders(
                        request = proxy["request"].stringMap(),
                        response = proxy["response"].stringMap(),
                    )
                },
            )
        }
        return try {
            Stream(
                url = root["url"].optionalString(),
                ytId = root["ytId"].optionalString(),
                infoHash = root["infoHash"].optionalString(),
                fileIdx = root["fileIdx"].nonNegativeInt(),
                externalUrl = root["externalUrl"].optionalString(),
                name = root["name"].optionalString(),
                title = root["title"].optionalString(),
                description = root["description"].optionalString(),
                sources = root["sources"].optionalStringArray().orEmpty(),
                subtitles = root["subtitles"].optionalArray().map { subtitle(it.record("stream subtitle")) },
                behaviorHints = behavior,
            )
        } catch (_: IllegalArgumentException) {
            throw AddonResponseValidationException("stream", "Stream has no playable source")
        }
    }

    private fun subtitle(root: JsonObject): Subtitle = Subtitle(
        id = root["id"].optionalString() ?: "default",
        url = root["url"].requiredString("subtitle URL"),
        lang = root["lang"].requiredString("subtitle language"),
    )

    private fun JsonObject.links(): List<MetaLink> = this["links"].optionalArray().map { element ->
        val link = element.record("meta link")
        MetaLink(
            name = link["name"].requiredString("link name"),
            category = link["category"].requiredString("link category"),
            url = link["url"].requiredString("link URL"),
        )
    }

    private fun JsonObject.trailers(): List<Trailer> = this["trailers"].optionalArray().map { element ->
        val trailer = element.record("trailer")
        Trailer(
            source = trailer["source"].requiredString("trailer source"),
            type = trailer["type"].requiredString("trailer type"),
        )
    }

    private fun parse(input: String, resource: String): JsonElement = try {
        json.parseToJsonElement(input)
    } catch (_: Throwable) {
        throw AddonResponseValidationException(resource, "Addon response is not valid JSON")
    }

    private fun JsonElement?.record(resource: String): JsonObject =
        this as? JsonObject ?: throw AddonResponseValidationException(resource, "$resource must be an object")

    private fun JsonElement?.array(resource: String): JsonArray =
        this as? JsonArray ?: throw AddonResponseValidationException(resource, "$resource must be an array")

    private fun JsonElement?.optionalArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    private fun JsonElement?.requiredString(field: String): String =
        optionalString() ?: throw AddonResponseValidationException("addon response", "Addon response is missing $field")

    private fun JsonElement?.optionalString(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.content.trim().takeIf(String::isNotEmpty)
    }

    private fun JsonElement?.stringArray(): List<String> =
        (this as? JsonArray)?.mapNotNull { it.optionalString() }
            ?: throw AddonResponseValidationException("addon response", "Expected a string array")

    private fun JsonElement?.optionalStringArray(): List<String>? =
        (this as? JsonArray)?.mapNotNull { it.optionalString() }

    private fun JsonElement?.optionalBoolean(): Boolean? =
        (this as? JsonPrimitive)?.content?.lowercase()?.let {
            when (it) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }

    private fun JsonElement?.number(): Double? = optionalString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
    private fun JsonElement?.nonNegativeInt(): Int? = number()?.takeIf { it >= 0 }?.toInt()
    private fun JsonElement?.nonNegativeLong(): Long? = number()?.takeIf { it >= 0 }?.toLong()
    private fun JsonObject.cacheLong(name: String): Long? = this[name].nonNegativeLong()

    private fun JsonElement?.posterShape(): PosterShape? = when (optionalString()?.lowercase()) {
        "square" -> PosterShape.Square
        "poster" -> PosterShape.Poster
        "landscape" -> PosterShape.Landscape
        else -> null
    }

    private fun JsonElement?.stringMap(): Map<String, String> =
        (this as? JsonObject)?.mapNotNull { (key, value) -> value.optionalString()?.let { key to it } }?.toMap().orEmpty()
}
