package streaming.identity.security;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalServiceTokenFilter extends OncePerRequestFilter {
    private final Map<String,String> tokens=new HashMap<>();
    public InternalServiceTokenFilter(String config) {
        Arrays.stream(config.split(",")).map(s->s.split("=",2)).filter(a->a.length==2).forEach(a->tokens.put(a[0].trim(),a[1].trim()));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/internal/"); }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String name=request.getHeader("X-Service-Name"), token=request.getHeader("X-Service-Token"), expected=tokens.get(name);
        if(expected==null || token==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"SERVICE_UNAUTHORIZED\",\"message\":\"Servicio no autorizado\"}"); return;
        }
        chain.doFilter(request,response);
    }
}
