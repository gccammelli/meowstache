package io.github.gccammelli.meowstache

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ClipBoundaryTest {
    private class Media : HaBackend {
        override val base="https://ha.example"
        var result: JsonElement?=null
        var error: ApiException?=null
        var request: JsonObject?=null
        override suspend fun get(path: String)=error("No REST request expected")
        override suspend fun post(path: String,body: String)=error("No service request expected")
        override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
            request=commands.single(); error?.let { throw it }; return listOf(result)
        }
    }

    @Test fun malformedCalendarDatesAreIgnoredAlongsideNonVideoFiles() {
        for (name in listOf("2026-02-30T17-09-33Z.mp4","2026-10-15T25-09-33Z.mp4","2026-10-15T17-61-33Z.mp4")) {
            assertNull(clipTime(name),name)
        }
        for (extension in listOf("mov","m4v","webm","mkv")) assertEquals(1_792_084_173_000L,clipTime("2026-10-15T17-09-33Z.$extension"))
    }

    @Test fun browsingSkipsIncompleteRowsAndSortsClipsByTheirRecordedTime() = runTest {
        val media=Media()
        media.result=json.parseToJsonElement("""{"children":[{},
            {"title":"notes.txt","media_content_id":"notes"},
            {"media_content_id":"2026-10-15T17-10-33Z.mp4"},
            {"title":"2026-10-15T17-09-33Z.mp4","media_content_id":"early"},
            {"title":"2026-02-30T17-09-33Z.mp4","media_content_id":"invalid-date"}]}""")
        val clips=media.clips("/meowstache/")
        assertEquals(listOf("early","2026-10-15T17-10-33Z.mp4"),clips.map { it.id })
        assertEquals("media-source://media_source/local/meowstache",media.request!!.s("media_content_id"))
    }

    @Test fun missingFolderIsEmptyButPermissionAndTransportFailuresRemainErrors() = runTest {
        val media=Media()
        assertTrue(media.clips("folder").isEmpty())
        media.result=JsonPrimitive("not an object"); assertTrue(media.clips("folder").isEmpty())
        media.result=json.parseToJsonElement("{}"); assertTrue(media.clips("folder").isEmpty())
        media.error=ApiException("Folder does not exist"); assertTrue(media.clips("folder").isEmpty())
        media.error=ApiException("Permission denied")
        assertEquals("Permission denied",assertFailsWith<ApiException> { media.clips("folder") }.message)
    }

    @Test fun resolvedVideoUrlsKeepTheSignedLinkAndRequestOneHourExpiry() = runTest {
        val media=Media(); val clip=Clip(0,"clip-id")
        for (url in listOf("/signed/clip.mp4","https://signed.example/clip.mp4")) {
            media.result=buildJsonObject { put("url",url) }
            assertEquals(if(url.startsWith("/")) media.base+url else url,media.clipUrl(clip))
            assertEquals("3600",media.request!!.s("expires")); assertEquals(clip.id,media.request!!.s("media_content_id"))
        }
        for (result in listOf(null,JsonObject(emptyMap()))) {
            media.result=result
            assertEquals("Home Assistant couldn't find that video",assertFailsWith<ApiException> { media.clipUrl(clip) }.message)
        }
    }

    @Test fun invalidVisitDurationsUseTheNormalWindowAndClipGapsStaySeparate() {
        val weighed=1_792_084_173_000L
        val clips=listOf(Clip(weighed-60_000,"first"),Clip(weighed,"nearest"),Clip(weighed+120_000,"next-visit"))
        for (duration in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-1.0)) {
            assertEquals(clips.take(2),clipsFor(weighed,clips,duration))
        }
        assertTrue(clipsFor(weighed,emptyList(),0.0).isEmpty())
    }
}
