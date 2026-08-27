package com.getair.stremio

import com.getair.stremio.model.AddonManifest
import com.getair.stremio.model.CatalogResponse
import com.getair.stremio.model.MetaResponse
import com.getair.stremio.model.StreamResponse
import com.getair.stremio.model.SubtitlesResponse

data class AddonRequestOptions(
    val bypassCache: Boolean = false,
)

interface StremioAddonClient {
    suspend fun manifest(options: AddonRequestOptions = AddonRequestOptions()): AddonManifest

    suspend fun catalog(
        type: String,
        id: String,
        extra: Map<String, List<String>> = emptyMap(),
        options: AddonRequestOptions = AddonRequestOptions(),
    ): CatalogResponse

    suspend fun meta(
        type: String,
        id: String,
        options: AddonRequestOptions = AddonRequestOptions(),
    ): MetaResponse

    suspend fun streams(
        type: String,
        id: String,
        options: AddonRequestOptions = AddonRequestOptions(),
    ): StreamResponse

    suspend fun subtitles(
        type: String,
        id: String,
        extra: Map<String, List<String>> = emptyMap(),
        options: AddonRequestOptions = AddonRequestOptions(),
    ): SubtitlesResponse
}
