package streaming.channels.domain;

/** Channel value constraints and projection ordering, independent from HTTP, storage and Spring. */
public final class ChannelRules {
    public static final int MAX_DESCRIPTION=500;
    private ChannelRules() { }
    public static boolean isExternalId(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    public static String description(String value) {
        if(value==null) return null;
        if(value.codePointCount(0,value.length())>MAX_DESCRIPTION) throw new IllegalArgumentException("description admite hasta 500 caracteres.");
        return value;
    }
    /** Public channel state: LIVE while the session is playable or reconnecting; any other availability is OFFLINE. */
    public static boolean isLive(String availability) { return "PLAYABLE".equals(availability) || "RECONNECTING".equals(availability); }
    /** Lifecycle events are ordered by streamGeneration first and then by sessionVersion inside the same session. */
    public static boolean isNewerLifecycle(long currentGeneration,String currentSession,long currentVersion,
            long generation,String session,long version) {
        if(generation!=currentGeneration) return generation>currentGeneration;
        if(currentSession==null || !currentSession.equals(session)) return true;
        return version>currentVersion;
    }
}
