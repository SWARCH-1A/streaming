package streaming.channels.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Service authentication for /internal/**: provisioning is reserved to Identity and stream events to Streaming.
 * These routes must not be published by the public reverse proxy.
 */
public class InternalServiceTokenFilter extends OncePerRequestFilter {
    private final Map<String,String> tokens=new HashMap<>();
    public InternalServiceTokenFilter(String config) {
        Arrays.stream(config.split(",")).map(s->s.split("=",2)).filter(a->a.length==2).forEach(a->tokens.put(a[0].trim(),a[1].trim()));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String name=request.getHeader("X-Service-Name"), token=request.getHeader("X-Service-Token"), expected=name==null?null:tokens.get(name);
        if(expected==null || token==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) {
            reject(response,401,"SERVICE_UNAUTHORIZED","Servicio no autorizado"); return;
        }
        String uri=request.getRequestURI();
        String required=uri.startsWith("/internal/channels/stream-events")?"streaming":uri.startsWith("/internal/channels/provision")?"identity":null;
        if(required==null || !required.equals(name)) { reject(response,403,"SERVICE_FORBIDDEN","Servicio sin permiso para esta operación"); return; }
        chain.doFilter(request,response);
    }
    private static void reject(HttpServletResponse response,int status,String code,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\""+code+"\",\"message\":\""+message+"\"}");
    }
}
