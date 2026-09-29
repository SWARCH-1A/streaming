package streaming.identity.security;

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
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8(); }
    @Bean InternalServiceTokenFilter internalServiceTokenFilter(@Value("${identity.internal-service-tokens}") String config) { return new InternalServiceTokenFilter(config); }
    @Bean FilterRegistrationBean<InternalServiceTokenFilter> disableServletFilterRegistration(InternalServiceTokenFilter filter) {
        FilterRegistrationBean<InternalServiceTokenFilter> registration=new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,
            InternalServiceTokenFilter internalFilter,
            @Value("${identity.secure-cookie:false}") boolean secureCookie) throws Exception {
        CookieCsrfTokenRepository csrf=CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieCustomizer(cookie->cookie.sameSite("Lax").secure(secureCookie).path("/"));
        http.addFilterBefore(internalFilter,org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .cors(Customizer.withDefaults()).csrf(c->c.csrfTokenRepository(csrf).ignoringRequestMatchers("/internal/**"))
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
            .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/api/identity/registrations/**","/api/identity/public/**","/api/identity/csrf","/actuator/health","/actuator/health/**","/actuator/info").permitAll()
                    .requestMatchers("/api/identity/registrations","/api/identity/sessions","/internal/**").permitAll()
                    .requestMatchers(HttpMethod.DELETE,"/api/identity/sessions/current").permitAll().anyRequest().denyAll());
        return http.build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${identity.public-origin}") String origin) {
        CorsConfiguration c=new CorsConfiguration(); c.setAllowedOrigins(List.of(origin)); c.setAllowedMethods(List.of("GET","POST","DELETE","OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type","Idempotency-Key","X-XSRF-TOKEN","X-Request-Id")); c.setExposedHeaders(List.of("X-Request-Id","Retry-After")); c.setAllowCredentials(true); c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",c); return source;
    }
}
