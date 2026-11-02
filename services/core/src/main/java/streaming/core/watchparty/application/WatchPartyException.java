package streaming.core.watchparty.application;

import org.springframework.http.HttpStatus;

public class WatchPartyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public WatchPartyException(HttpStatus status,String code,String message) {
        super(message); this.status=status; this.code=code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
