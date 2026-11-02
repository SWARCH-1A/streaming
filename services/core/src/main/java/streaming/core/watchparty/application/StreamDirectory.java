package streaming.core.watchparty.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only view of Streaming. Streaming owns stream state; Core never stores or decides it. */
public interface StreamDirectory {
    Lookup find(String streamId);

    /** Looks every stream up; implementations may do it concurrently. Never throws for a remote failure. */
    default Map<String,Lookup> findAll(Collection<String> streamIds) {
        Map<String,Lookup> found=new LinkedHashMap<>();
        for(String streamId:streamIds) found.put(streamId,find(streamId));
        return found;
    }

    enum Outcome { FOUND, NOT_FOUND, UNAVAILABLE }

    /** {@code sessionId} is the current session of the stream, or null while it has none. */
    record StreamSnapshot(String streamId,String channelId,String sessionId,String title,String category,String status,
            String availability,boolean statusFresh,Integer viewerCount) {
        /** A source can be consumed only while Streaming confirms a fresh LIVE and PLAYABLE session. */
        public boolean playable() {
            return statusFresh && "LIVE".equals(status) && "PLAYABLE".equals(availability) && sessionId!=null;
        }
    }

    record Lookup(Outcome outcome,StreamSnapshot snapshot) {
        public static Lookup found(StreamSnapshot snapshot) { return new Lookup(Outcome.FOUND,snapshot); }
        public static Lookup notFound() { return new Lookup(Outcome.NOT_FOUND,null); }
        public static Lookup unavailable() { return new Lookup(Outcome.UNAVAILABLE,null); }
    }
}
