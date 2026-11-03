package streaming.mobile

import kotlin.test.*
import streaming.mobile.contracts.*

class CommunityTest {
    @Test fun notificationDestinationsStayInsidePublicChannels() {
        assertEquals("channel:fixture", notificationDestination("/channels/fixture"))
        listOf("https://example.test/channels/fixture", "//evil.test", "/channels/../admin", "/channels/fixture?token=secret", "/api/admin").forEach {
            assertNull(notificationDestination(it))
        }
    }
    @Test fun subtitleTimingSupportsSeekAndRejectsMarkupOrMalformedRanges() {
        val cues = subtitleCues("WEBVTT\n\n00:01.100 --> 00:02.500\nHola\n\n00:01:03.000 --> 00:01:04.000\nOtro mensaje\n")
        assertEquals(1100L, cues[0].fromMs)
        assertEquals(2500L, cues[0].toMs)
        assertEquals(63000L, cues[1].fromMs)
        assertFails { subtitleCues("WEBVTT\n\n00:02.000 --> 00:01.000\nHola") }
        assertFails { subtitleCues("WEBVTT\n\n00:01.000 --> 00:02.000\n<b>Hola</b>") }
        assertFails { subtitleCues("WEBVTT\n\n00:99.000 --> 00:99.500\nHola") }
    }
    @Test fun subtitleProfileSupportsIdentifiersCommentsLineEndingsAndTextEntities() {
        val cues = subtitleCues("\uFEFFWEBVTT perfil\r\rNOTE comentario\rcontinúa\r\rcue-1\r00:00.000\t-->\t00:01.000\rA &amp; B &lt;texto&gt; &nbsp;\r\r00:00.500 --> 00:02.000\rSuperpuesto", 2000)
        assertEquals("A & B <texto> \u00a0", cues[0].text)
        assertEquals(500L, cues[1].fromMs)
        listOf("WEBVTTmal\n\n00:00.000 --> 00:01.000\nHola", "WEBVTT\n\nNOTE --> inválida", "WEBVTT\n\n00:00.000 --> 00:01.000\n&#60;", "WEBVTT\n\n00:00.000 --> 00:01.001\nFuera").forEach {
            assertFails { subtitleCues(it, 1000) }
        }
    }
    @Test fun emptyLibraryFiltersAreOmittedAndValuesEncoded() {
        assertEquals("/api/screens/explore?limit=20", libraryPath("", "", "", null))
        assertEquals("/api/screens/explore?limit=20&title=A%26B&categoryId=cat_1&cursor=next%2Bpage", libraryPath("A&B", "cat_1", "", "next+page"))
    }
    @Test fun partialGatewayFailureIsNotAnEmptyLibrary() {
        val unavailable = wireJson.parseToJsonElement("""{"status":"UNAVAILABLE","error":{"code":"VOD_UNAVAILABLE","requestId":"fixture"}}""").let { it as kotlinx.serialization.json.JsonObject }
        assertNull(unavailable.sectionData<P2VodPage>())
        val empty = wireJson.parseToJsonElement("""{"status":"EMPTY","data":{"items":[],"nextCursor":null}}""").let { it as kotlinx.serialization.json.JsonObject }
        assertEquals(emptyList(), empty.sectionData<P2VodPage>()?.items)
    }
}
