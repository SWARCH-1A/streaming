package streaming.core.watchparty.domain;

/** Watch Party constraints (SPEC-14), independent from HTTP, storage and Spring. */
public final class WatchPartyRules {
    public static final int MAX_STREAMS=4;
    public static final int MAX_TITLE=100;
    /** 32 random bytes, base64url without padding. */
    public static final int ACCESS_CODE_LENGTH=43;
    public static final String OPEN="OPEN";
    public static final String CLOSED="CLOSED";
    private WatchPartyRules() { }

    public static boolean isPartyId(String value) { return value!=null && value.matches("wp_[0-9a-f]{32}"); }
    /** Stream and channel IDs are opaque for Core: only their safe shape is checked. */
    public static boolean isExternalId(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{1,64}"); }
    public static boolean isAccessCode(String value) { return value!=null && value.matches("[A-Za-z0-9_-]{"+ACCESS_CODE_LENGTH+"}"); }

    /** Returns the stripped title or explains why it is invalid. */
    public static String title(String value) {
        if(value==null) throw new IllegalArgumentException("title es obligatorio.");
        String title=value.strip();
        if(title.isEmpty()) throw new IllegalArgumentException("title no puede estar vacío.");
        if(title.codePointCount(0,title.length())>MAX_TITLE) throw new IllegalArgumentException("title admite hasta 100 caracteres.");
        if(title.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("title no admite caracteres de control.");
        return title;
    }
}
