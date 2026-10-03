package streaming.core.accounts.identity.domain;

import java.util.Locale;

/** Pure domain rules for canonical identity values; contains no web or persistence types. */
public final class IdentityRules {
    private IdentityRules() { }
    public static String canonicalEmail(String value) {
        if(value==null) throw new IllegalArgumentException("Correo obligatorio.");
        String email=value.trim().toLowerCase(Locale.ROOT);
        if(email.length()>320 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new IllegalArgumentException("Correo inválido.");
        return email;
    }
    public static String canonicalHandle(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_]{4,25}")) throw new IllegalArgumentException("El handle debe tener entre 4 y 25 caracteres ASCII alfanuméricos o _.");
        return value.toLowerCase(Locale.ROOT);
    }
    public static void validatePassword(String value) {
        if(value==null) throw new IllegalArgumentException("Contraseña obligatoria.");
        int points=value.codePointCount(0,value.length());
        if(points<12 || points>128) throw new IllegalArgumentException("La contraseña debe tener entre 12 y 128 puntos de código Unicode.");
    }
}
