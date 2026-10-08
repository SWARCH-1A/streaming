package streaming.core.infrastructure;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import streaming.core.accounts.identity.application.StreamingSessions;
import streaming.core.channels.application.StreamingChannelSnapshots;
import tools.jackson.databind.JsonNode;
import static streaming.core.infrastructure.StreamingFields.*;

@Component
public class HttpStreamingReads implements StreamingSessions,StreamingChannelSnapshots {
    private final PrivateStreamingHttp http;
    public HttpStreamingReads(PrivateStreamingHttp http) { this.http=http; }

    @Override public StreamingSessions.Snapshot session(String sessionId,String requestId) {
        JsonNode n;
        try { n=http.exchange("/internal/streaming/sessions/"+sessionId+"/context",null,Duration.ofMillis(200),requestId); }
        catch(PrivateStreamingHttp.Failure e) { throw new StreamingSessions.Unavailable(e.notFound()); }
        try {
            String sid=id(n,"sessionId"),stream=id(n,"streamId");
            long generation=number(n,"streamGeneration",1),version=number(n,"sessionVersion",1);
            String status=choice(n,"status","PREPARING","LIVE","ENDED"),availability=availability(n,status);
            if(!sid.equals(sessionId)) throw new IllegalArgumentException();
            long timeline; java.time.Instant sampled;
            try { timeline=number(n,"timelinePositionMs",0); sampled=instant(n,"timelineSampledAtUtc"); }
            catch(RuntimeException e) { throw new StreamingSessions.TimelineUnavailable(); }
            if(status.equals("LIVE") && (sampled.isBefore(java.time.Instant.now().minusSeconds(5))
                    || sampled.isAfter(java.time.Instant.now().plusSeconds(5)))) throw new StreamingSessions.TimelineUnavailable();
            return new StreamingSessions.Snapshot(stream,sid,generation,version,status,availability,timeline,sampled);
        } catch(StreamingSessions.TimelineUnavailable e) { throw e; }
        catch(RuntimeException e) { throw new StreamingSessions.Unavailable(false); }
    }

    @Override public StreamingChannelSnapshots.Snapshot channel(String channelId,String requestId) {
        JsonNode body;
        try { body=http.exchange("/internal/streaming/channels/snapshots",Map.of("channelIds",List.of(channelId)),Duration.ofSeconds(1),requestId); }
        catch(PrivateStreamingHttp.Failure e) { throw new StreamingChannelSnapshots.Unavailable(); }
        try {
            var items=body.get("items");
            if(items==null || !items.isArray() || items.size()!=1) throw new IllegalArgumentException();
            var item=items.get(0);
            if(!channelId.equals(id(item,"channelId"))) throw new IllegalArgumentException();
            var observed=instant(item,"observedAtUtc");
            boolean configured=bool(item,"configured");
            if(!configured) {
                if(!nil(item,"stream") || !nil(item,"session")) throw new IllegalArgumentException();
                return new StreamingChannelSnapshots.Snapshot(false,null,observed);
            }
            var n=item.get("stream");
            if(n==null || !n.isObject() || !channelId.equals(id(n,"channelId"))) throw new IllegalArgumentException();
            var session=nil(item,"session")?null:publicSession(item.get("session"));
            String streamId=id(n,"streamId"),sessionId=optionalText(n,"sessionId");
            long generation=number(n,"streamGeneration",0),metadataVersion=number(n,"metadataVersion",1);
            Long sessionVersion=optionalNumber(n,"sessionVersion");
            String status=choice(n,"status","OFFLINE","PREPARING","LIVE","ENDED");
            String availability=availability(n,status);
            if(session==null) {
                if(sessionId!=null || sessionVersion!=null || !status.equals("OFFLINE")) throw new IllegalArgumentException();
            } else if(!session.sessionId().equals(sessionId) || !session.streamId().equals(streamId)
                    || !session.channelId().equals(channelId) || session.streamGeneration()!=generation
                    || sessionVersion==null || sessionVersion!=session.sessionVersion() || session.metadataVersion()!=metadataVersion
                    || !session.availability().equals(availability)
                    || !(status.equals(session.status()) || (status.equals("OFFLINE") && session.status().equals("ENDED"))))
                throw new IllegalArgumentException();
            var category=label(n.get("category"));
            var tags=n.get("tags");
            if(tags==null || !tags.isArray() || tags.size()>5) throw new IllegalArgumentException();
            var labels=new ArrayList<StreamingChannelSnapshots.Label>();
            for(var tag:tags) labels.add(label(tag));
            if(labels.stream().map(StreamingChannelSnapshots.Label::id).distinct().count()!=labels.size()) throw new IllegalArgumentException();
            String title=text(n,"title");
            if(title.codePointCount(0,title.length())>100) throw new IllegalArgumentException();
            var stream=new StreamingChannelSnapshots.Stream(streamId,channelId,sessionId,generation,title,category,labels,status,
                    availability,bool(n,"statusFresh"),metadataVersion,sessionVersion,optionalNumber(n,"viewerCount"),
                    optionalNumber(n,"countVersion"),optionalInstant(n,"viewerCountObservedAtUtc"),session);
            return new StreamingChannelSnapshots.Snapshot(true,stream,observed);
        } catch(RuntimeException e) { throw new StreamingChannelSnapshots.Unavailable(); }
    }

    private static StreamingChannelSnapshots.Label label(JsonNode n) {
        if(n==null || !n.isObject()) throw new IllegalArgumentException();
        return new StreamingChannelSnapshots.Label(id(n,"id"),text(n,"name"));
    }
    private static StreamingChannelSnapshots.Session publicSession(JsonNode n) {
        String status=choice(n,"status","PREPARING","LIVE","ENDED");
        String playback=optionalText(n,"playbackUrl");
        String sessionId=id(n,"sessionId");
        if(playback!=null) {
            var uri=java.net.URI.create(playback);
            if(uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getUserInfo()!=null
                    || (uri.isAbsolute() && (!java.util.Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null))
                    || (!uri.isAbsolute() && uri.getRawAuthority()!=null)
                    || !uri.getRawPath().matches("/hls/"+sessionId+"/[A-Za-z0-9_./-]+\\.m3u8")) throw new IllegalArgumentException();
            for(String segment:uri.getRawPath().substring(1).split("/",-1))
                if(segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException();
        }
        return new StreamingChannelSnapshots.Session(sessionId,id(n,"streamId"),id(n,"channelId"),number(n,"streamGeneration",1),
                status,availability(n,status),playback,number(n,"timelinePositionMs",0),optionalInstant(n,"timelineSampledAtUtc"),
                number(n,"metadataVersion",1),number(n,"sessionVersion",1),number(n,"viewerCount",0),number(n,"countVersion",0),
                optionalInstant(n,"viewerCountObservedAtUtc"));
    }
    private static String availability(JsonNode n,String status) {
        String value=choice(n,"availability","OFFLINE","PLAYABLE","RECONNECTING");
        if(status.equals("LIVE") ? value.equals("OFFLINE") : !value.equals("OFFLINE")) throw new IllegalArgumentException();
        return value;
    }
}
