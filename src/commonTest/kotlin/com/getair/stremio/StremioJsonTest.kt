package com.getair.stremio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StremioJsonTest {
    @Test
    fun normalizesStringAndObjectManifestResources() {
        val manifest = StremioJson.manifest(
            """{
              "id":"org.example","version":"1.0.0","name":"Example",
              "resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt"]}],
              "types":["movie"],
              "catalogs":[{"type":"movie","id":"popular","name":"Popular"}],
              "behaviorHints":{"p2p":true}
            }""",
        )

        assertEquals("catalog", manifest.resources[0].name)
        assertEquals(null, manifest.resources[0].types)
        assertEquals(listOf("movie"), manifest.resources[1].types)
        assertEquals(true, manifest.behaviorHints?.p2p)
    }

    @Test
    fun normalizesMetadataStreamsAndSubtitles() {
        val meta = StremioJson.meta(
            """{"meta":{
              "id":"tt0133093","type":"movie","name":"The Matrix","imdbRating":8.7,
              "genres":["Action"],"videos":[{"id":"tt0133093:1:1","title":"Pilot","season":1,"episode":1}]
            }}""",
        )
        val streams = StremioJson.streams(
            """{"streams":[
              {"url":"https://media.invalid/movie.mkv","title":"4K"},
              {"infoHash":"0123456789abcdef","fileIdx":0,"behaviorHints":{"filename":"movie.mkv"}}
            ]}""",
        )
        val subtitles = StremioJson.subtitles(
            """{"subtitles":[{"id":"en","url":"https://subs.invalid/en.srt","lang":"eng"}]}""",
        )

        assertEquals("8.7", meta.meta.imdbRating)
        assertEquals(1, meta.meta.videos.single().season)
        assertEquals("movie.mkv", streams.streams[1].behaviorHints?.filename)
        assertEquals("eng", subtitles.subtitles.single().lang)
        assertFalse("media.invalid" in streams.toString())
        assertFalse("subs.invalid" in subtitles.toString())
    }

    @Test
    fun rejectsAStreamWithoutAPlayableSource() {
        assertFailsWith<AddonResponseValidationException> {
            StremioJson.streams("""{"streams":[{"title":"broken"}]}""")
        }
    }
}
