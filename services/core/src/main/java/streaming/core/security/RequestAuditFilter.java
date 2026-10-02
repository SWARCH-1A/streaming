package streaming.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestAuditFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_ATTRIBUTE="streaming.requestId";
    private static final Logger log=LoggerFactory.getLogger(RequestAuditFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String requestId=request.getHeader("X-Request-Id");
        if(requestId==null || !requestId.matches("[A-Za-z0-9._-]{1,80}")) requestId=UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTRIBUTE,requestId); response.setHeader("X-Request-Id",requestId);
        long started=System.nanoTime();
        try { chain.doFilter(request,response); }
        finally {
            Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            log.info("event=http_request component=core requestId={} method={} route={} status={} durationMs={}",
                    requestId,request.getMethod(),route==null?"unmatched":route,response.getStatus(),(System.nanoTime()-started)/1_000_000);
        }
    }
}
