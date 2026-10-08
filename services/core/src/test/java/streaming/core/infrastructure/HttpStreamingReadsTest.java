package streaming.core.infrastructure;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import streaming.core.accounts.identity.application.StreamingSessions;
import streaming.core.channels.application.StreamingChannelSnapshots;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpStreamingReadsTest {
    ObjectMapper json=new ObjectMapper();
    PrivateStreamingHttp http=mock(PrivateStreamingHttp.class);
    HttpStreamingReads reads=new HttpStreamingReads(http);
    tools.jackson.databind.node.ObjectNode session() {
        return json.createObjectNode().put("streamId","str_a").put("sessionId","ses_a").put("streamGeneration",1)
                .put("sessionVersion",2).put("status","LIVE").put("availability","RECONNECTING")
                .put("timelinePositionMs",42).put("timelineSampledAtUtc",Instant.now().toString());
    }
    void response(tools.jackson.databind.JsonNode n) { when(http.exchange(anyString(),any(),any(),any())).thenReturn(n); }
    @Test void sessionPreservesGraceAndTimelineButRejectsIdentityVersionOrClockCorruption() {
        response(session()); assertThat(reads.session("ses_a",null).availability()).isEqualTo("RECONNECTING");
        for(var n:java.util.List.of(session().put("sessionId","ses_other"),session().put("streamGeneration",0),
                session().put("sessionVersion","2"),session().put("availability","OFFLINE"))) {
            response(n); assertThatThrownBy(()->reads.session("ses_a",null)).isInstanceOf(StreamingSessions.Unavailable.class);
        }
        for(var n:java.util.List.of(session().put("timelinePositionMs",-1),session().putNull("timelineSampledAtUtc"),
                session().put("timelineSampledAtUtc","2026-10-08T17:00:00-05:00"))) {
            response(n); assertThatThrownBy(()->reads.session("ses_a",null)).isInstanceOf(StreamingSessions.TimelineUnavailable.class);
        }
    }
    @Test void batchAbsenceMustBeExplicitAndMatchRequestedChannel() {
        var item=json.createObjectNode().put("channelId","chn_a").put("configured",false).putNull("stream").putNull("session").put("observedAtUtc",Instant.now().toString());
        var batch=json.createObjectNode(); batch.putArray("items").add(item); response(batch);
        assertThat(reads.channel("chn_a",null).configured()).isFalse();
        item.put("channelId","chn_other");
        assertThatThrownBy(()->reads.channel("chn_a",null)).isInstanceOf(StreamingChannelSnapshots.Unavailable.class);
        item.put("channelId","chn_a").remove("stream");
        assertThatThrownBy(()->reads.channel("chn_a",null)).isInstanceOf(StreamingChannelSnapshots.Unavailable.class);
    }
    @Test void bootstrapAcceptsRelativeAndAbsolutePublicHlsButRejectsTraversalCredentialsOrForeignSession() {
        for(String url:java.util.List.of("/hls/ses_a/index.m3u8","https://public.example.test/hls/ses_a/index.m3u8",
                "https://public.example.test/hls/ses_a/../other.m3u8","https://user:secret@public.example.test/hls/ses_a/index.m3u8",
                "//private.example.test/hls/ses_a/index.m3u8","/hls/ses_other/index.m3u8")) {
            var s=session().put("channelId","chn_a").put("availability","PLAYABLE").put("metadataVersion",1).put("viewerCount",0).put("countVersion",0)
                    .putNull("viewerCountObservedAtUtc").put("playbackUrl",url);
            var stream=s.deepCopy().put("statusFresh",true).put("title","Emisión");
            stream.putObject("category").put("id","cat_a").put("name","Categoría");stream.putArray("tags");
            var item=json.createObjectNode().put("channelId","chn_a").put("configured",true).put("observedAtUtc",Instant.now().toString());
            item.set("stream",stream);item.set("session",s);var batch=json.createObjectNode();batch.putArray("items").add(item);response(batch);
            if(url.equals("/hls/ses_a/index.m3u8") || url.equals("https://public.example.test/hls/ses_a/index.m3u8"))
                assertThat(reads.channel("chn_a",null).stream().session().playbackUrl()).isEqualTo(url);
            else assertThatThrownBy(()->reads.channel("chn_a",null)).isInstanceOf(StreamingChannelSnapshots.Unavailable.class);
        }
    }
}
