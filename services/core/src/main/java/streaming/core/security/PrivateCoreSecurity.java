package streaming.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import streaming.core.api.CoreErrorHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class PrivateCoreSecurity {
    @Bean @Order(1)
    SecurityFilterChain privateSecurity(HttpSecurity http,PrivateCoreListener listener,ObjectMapper json) throws Exception {
        http.securityMatcher("/internal/**")
                .csrf(c->c.disable()).cors(c->c.disable())
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c->c.disable())
                .authorizeHttpRequests(a->a
                        .requestMatchers(HttpMethod.POST,"/internal/core/streaming/owner-context")
                        .access((authentication,context)->new AuthorizationDecision(listener.permits(context.getRequest(),PrivateCoreListener.Permission.OWNER_CONTEXT)))
                        .requestMatchers(HttpMethod.POST,"/internal/core/streaming/catalog-values")
                        .access((authentication,context)->new AuthorizationDecision(listener.permits(context.getRequest(),PrivateCoreListener.Permission.CATALOG_VALUES)))
                        .requestMatchers(HttpMethod.POST,"/internal/core/discovery/stream-events")
                        .access((authentication,context)->new AuthorizationDecision(listener.permits(context.getRequest(),PrivateCoreListener.Permission.DISCOVERY_EVENTS)))
                        .anyRequest().denyAll())
                .exceptionHandling(e->e
                        .authenticationEntryPoint((request,response,error)->deny(request,response,listener,json))
                        .accessDeniedHandler((request,response,error)->deny(request,response,listener,json)));
        return http.build();
    }
    private static void deny(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,
            PrivateCoreListener listener,ObjectMapper json) throws java.io.IOException {
        boolean internal=listener.isPrivate(request);
        response.setStatus(internal?401:404);
        response.setContentType("application/json"); response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getWriter(),CoreErrorHandler.body(internal?"SERVICE_UNAUTHORIZED":"NOT_FOUND",
                "La solicitud no está autorizada.",request));
    }
}
