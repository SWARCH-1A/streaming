package streaming.channels.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestAuditFilter extends OncePerRequestFilter {
    private static final Logger log=LoggerFactory.getLogger(RequestAuditFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String requestId=request.getHeader("X-Request-Id");
        if(requestId==null || !requestId.matches("[A-Za-z0-9._-]{1,80}")) requestId=UUID.randomUUID().toString();
        response.setHeader("X-Request-Id",requestId); long started=System.nanoTime();
        try { chain.doFilter(request,response); }
        finally { log.info("event=http_request component=channels requestId={} method={} path={} status={} durationMs={}",requestId,request.getMethod(),request.getRequestURI(),response.getStatus(),(System.nanoTime()-started)/1_000_000); }
    }
}
