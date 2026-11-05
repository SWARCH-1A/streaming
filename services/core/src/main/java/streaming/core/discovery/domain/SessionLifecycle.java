package streaming.core.discovery.domain;

import java.time.Instant;

/** Discovery's local session barrier; its version is independent of the public projection version. */
public record SessionLifecycle(String streamId,String channelId,String sessionId,long streamGeneration,
        long sessionVersion,String status,String availability,Instant firstLiveAtUtc,Instant occurredAtUtc,
        String fingerprint) { }
