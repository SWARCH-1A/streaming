package streaming.core.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import streaming.core.api.CoreErrorHandler;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class CoreSecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,ObjectMapper json,
            @Value("${core.secure-cookie:false}") boolean secureCookie) throws Exception {
        CookieCsrfTokenRepository csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie->cookie.sameSite("Lax").secure(secureCookie).path("/"));
        http.cors(Customizer.withDefaults())
            // The JSON endpoint and cookie expose the same token accepted by X-XSRF-TOKEN.
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable())
            .authorizeHttpRequests(a->a
                    .requestMatchers(HttpMethod.GET,"/api/identity/registrations/**","/api/identity/public/**",
                            "/api/identity/csrf","/api/profile/csrf","/api/profile/me","/api/profile/users/**",
                            "/api/profile/avatars/**","/api/channels/by-handle/*","/api/channels/by-owner/*",
                            "/api/channels/csrf","/api/channels/banners/*",
                            "/actuator/health","/actuator/health/**","/actuator/info").permitAll()
                    .requestMatchers(HttpMethod.POST,"/api/identity/registrations","/api/identity/sessions",
                            "/api/profile/me/avatar-uploads","/api/channels/*/banner-uploads").permitAll()
                    .requestMatchers(HttpMethod.PATCH,"/api/profile/me","/api/channels/*").permitAll()
                    .requestMatchers(HttpMethod.DELETE,"/api/identity/sessions/current").permitAll()
                    .anyRequest().denyAll())
            .exceptionHandling(e->e.accessDeniedHandler((request,response,error)-> {
                response.setStatus(403); response.setContentType("application/json");
                boolean csrfError=error instanceof org.springframework.security.web.csrf.CsrfException;
                json.writeValue(response.getWriter(),CoreErrorHandler.body(
                        csrfError?"CSRF_INVALID":"ACCESS_DENIED","La solicitud no está autorizada.",request));
            }));
        return http.build();
    }

    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${core.public-origin}") String origin) {
        CorsConfiguration c=new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin)); c.setAllowedMethods(List.of("GET","POST","PATCH","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type","Idempotency-Key","X-XSRF-TOKEN","X-Request-Id"));
        c.setExposedHeaders(List.of("X-Request-Id","Retry-After")); c.setAllowCredentials(true); c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",c); return source;
    }
}
