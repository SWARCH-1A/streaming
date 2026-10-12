package streaming.core.channels.domain;

/** Channel value constraints, independent from HTTP, storage and Spring. */
public final class ChannelRules {
    public static final int MAX_DESCRIPTION=500;
    private ChannelRules() { }
    public static boolean isExternalId(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    public static String description(String value) {
        if(value==null) return "";
        if(value.codePointCount(0,value.length())>MAX_DESCRIPTION) throw new IllegalArgumentException("description admite hasta 500 caracteres.");
        return value;
    }
}
