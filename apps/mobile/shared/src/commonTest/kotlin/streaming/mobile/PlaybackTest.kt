package streaming.mobile

import streaming.mobile.contracts.ChatAuthor
import streaming.mobile.contracts.ChatMessage
import kotlin.test.*

class PlaybackTest {
    @Test fun contentLinksAcceptOnlyKnownRoutesAndOpaqueIdentifiers() {
        assertEquals("watch:str_fixture", destinationRoute("vault://watch/str_fixture"))
        assertEquals("channel:fixture", destinationRoute("vault://channel/fixture"))
        listOf("https://evil.example/watch/str_fixture", "vault://watch/../account", "vault://watch/%2fsecret",
            "vault://login/fixture", "vault://watch/str_fixture?token=secret", "vault://channel/fixture#fragment").forEach {
            assertNull(destinationRoute(it))
        }
    }

    private fun message(sequence: Long) = ChatMessage("msg_$sequence", "ses_fixture", sequence,
        ChatAuthor("usr_fixture", "fixture_01", "Fixture", null), "Mensaje $sequence", "2026-10-27T12:00:00Z", 1000, 1)
    @Test fun tombstonesAndOtherRoomsCannotResurrectMessages() {
        val result = mergeChat("ses_fixture", listOf(message(1)), listOf(message(1), message(2), message(3).copy(sessionId = "ses_other")), setOf("msg_1"))
        assertEquals(listOf(2L), result.map { it.sequence })
        assertTrue(hasGap(listOf(message(1), message(3))))
        assertFalse(hasGap(listOf(message(1), message(2))))
    }
    @Test fun masterOffersOnlyReceivedQualities() {
        val master = """#EXTM3U
            |#EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            |720/index.m3u8
            |#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            |360/index.m3u8
        """.trimMargin()
        val result = hlsRenditions("https://fixture.example.test/hls/ses_fixture/master.m3u8", master)
        assertEquals(listOf("720p", "360p"), result.map { it.label })
        assertFalse(result.any { it.label == "1080p" })
        assertFailsWith<IllegalArgumentException> { hlsRenditions("https://fixture.example.test/hls/ses_fixture/master.m3u8", master.replace("720/index.m3u8", "https://evil.example.test/private.m3u8")) }
        assertFailsWith<IllegalArgumentException> { hlsRenditions("https://fixture.example.test/hls/ses_fixture/master.m3u8", master.replace("720/index.m3u8", "../other.m3u8")) }
    }
    @Test fun messageLengthCountsCodePointsAndNormalizesNfc() {
        assertEquals(1, codePoints("😀")); assertEquals("é", normalizeText("e\u0301"))
        assertEquals(500, codePoints("😀".repeat(500)))
    }
}
