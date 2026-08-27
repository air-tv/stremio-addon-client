package com.getair.stremio.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesMetadataShapeUsedByTheDesignApp() {
        val response = json.decodeFromString<MetaResponse>(
            """{"meta":{"id":"tt0133093","type":"movie","name":"The Matrix","posterShape":"poster","imdbRating":"8.7","genres":["Action","Sci-Fi"]}}""",
        )

        assertEquals("The Matrix", response.meta.name)
        assertEquals(PosterShape.Poster, response.meta.posterShape)
        assertEquals(listOf("Action", "Sci-Fi"), response.meta.genres)
    }

    @Test
    fun streamRequiresAPlayableSource() {
        assertFailsWith<IllegalArgumentException> { Stream(title = "missing source") }
    }
}
