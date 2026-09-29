package streaming.profile.domain;

/** Profile value constraints, independent from HTTP, storage and Spring. */
public final class ProfileRules {
    private ProfileRules() { }
    public static String displayName(String value) {
        if(value==null) throw new IllegalArgumentException("displayName debe ser texto no nulo.");
        int points=value.codePointCount(0,value.length());
        if(points<1 || points>50) throw new IllegalArgumentException("displayName debe tener entre 1 y 50 caracteres.");
        return value;
    }
    public static String bio(String value) {
        String normalized=value==null?"":value;
        if(normalized.codePointCount(0,normalized.length())>300) throw new IllegalArgumentException("bio admite hasta 300 caracteres.");
        return normalized;
    }
}
