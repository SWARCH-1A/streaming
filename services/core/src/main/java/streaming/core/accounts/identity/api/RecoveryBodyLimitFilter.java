package streaming.core.accounts.identity.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import streaming.core.api.CoreErrorHandler;
import tools.jackson.databind.ObjectMapper;

/** Runs after Spring Security: private service permission and browser CSRF precede body parsing. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RecoveryBodyLimitFilter extends OncePerRequestFilter {
    private static final int LIMIT = 16_384;
    private final ObjectMapper json;
    public RecoveryBodyLimitFilter(ObjectMapper json) { this.json = json; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !((request.getMethod().equals("POST") || request.getMethod().equals("PUT"))
                && (path.equals("/api/identity/recovery-requests") || path.equals("/api/identity/recoveries/confirm")
                || path.matches("/internal/core/recovery-deliveries/[^/]+/(claim|result)")));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > LIMIT) { reject(request, response); return; }
        byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) { reject(request, response); return; }
        var input = new ByteArrayInputStream(bytes);
        var stream = new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous JSON request."); }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return stream; }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); }
        }, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), CoreErrorHandler.body("PAYLOAD_TOO_LARGE", "La solicitud excede 16 KiB.", request));
    }
}
