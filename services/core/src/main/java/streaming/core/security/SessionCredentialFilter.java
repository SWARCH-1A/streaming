package streaming.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;
import streaming.core.accounts.identity.application.IdentityApplicationService;
import streaming.core.api.CoreErrorHandler;
import tools.jackson.databind.ObjectMapper;

/** Resolves user credentials at the public boundary; never accepts service credentials. */
public final class SessionCredentialFilter extends OncePerRequestFilter {
    public static final String CREDENTIAL="streaming.sessionCredential";
    public static final String NATIVE="streaming.nativeSession";
    private final IdentityApplicationService identity;
    private final ObjectMapper json;
    private final String cookieName;

    public SessionCredentialFilter(IdentityApplicationService identity,ObjectMapper json,String cookieName) {
        this.identity=identity; this.json=json; this.cookieName=cookieName;
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String cookie=null;
        if(request.getCookies()!=null) for(Cookie candidate:request.getCookies()) if(cookieName.equals(candidate.getName())) {
            if(cookie!=null) { deny(request,response,401,"AUTH_REQUIRED"); return; }
            cookie=candidate.getValue();
        }
        var headers=Collections.list(request.getHeaders("Authorization"));
        if(!headers.isEmpty()) {
            String header=headers.getFirst();
            // Opaque Core credentials are 256-bit base64url, without padding.
            if(headers.size()!=1 || !header.matches("(?i:Bearer) [A-Za-z0-9_-]{43}") || cookie!=null) {
                deny(request,response,401,"AUTH_REQUIRED"); return;
            }
            String credential=header.substring(7);
            try {
                if(!request.getRequestURI().equals("/api/identity/mobile-sessions/current") && identity.introspect(credential).isEmpty()) { deny(request,response,401,"AUTH_REQUIRED"); return; }
            } catch(DataAccessException unavailable) { deny(request,response,503,"CORE_UNAVAILABLE"); return; }
            request.setAttribute(CREDENTIAL,credential);
            request.setAttribute(NATIVE,Boolean.TRUE);
        } else if(cookie!=null) request.setAttribute(CREDENTIAL,cookie);
        chain.doFilter(request,response);
    }
    /** Cookies and browser Origin always retain the browser CSRF policy. */
    public static boolean nativeRequest(HttpServletRequest request) {
        if(request.getHeader("Origin")!=null || request.getHeader("Cookie")!=null) return false;
        if(Boolean.TRUE.equals(request.getAttribute(NATIVE))) return true;
        String path=request.getRequestURI();
        return ("POST".equals(request.getMethod()) && (path.equals("/api/identity/mobile-sessions")
                || path.equals("/api/identity/registrations") || path.equals("/api/identity/recovery-requests")
                || path.equals("/api/identity/recoveries/confirm")))
                || ("DELETE".equals(request.getMethod()) && path.equals("/api/identity/mobile-sessions/current"));
    }
    private void deny(HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8"); response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getWriter(),CoreErrorHandler.body(code,
                status==401?"Se requiere una sesión activa.":"No fue posible comprobar la sesión.",request));
    }
}
