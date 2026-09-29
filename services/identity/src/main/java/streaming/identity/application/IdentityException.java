package streaming.identity.application;

import org.springframework.http.HttpStatus;

public class IdentityException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;
    public IdentityException(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }
    public IdentityException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message); this.status=status; this.code=code; this.retryAfterSeconds=retryAfterSeconds;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
