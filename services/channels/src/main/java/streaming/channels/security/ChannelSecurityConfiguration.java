package streaming.channels.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** User identity is validated per operation through Identity introspection; internal routes use service tokens. */
@Configuration
public class ChannelSecurityConfiguration {
    @Bean InternalServiceTokenFilter internalServiceTokenFilter(@Value("${channels.internal-service-tokens}") String config) { return new InternalServiceTokenFilter(config); }
    @Bean FilterRegistrationBean<InternalServiceTokenFilter> disableServletFilterRegistration(InternalServiceTokenFilter filter) {
        FilterRegistrationBean<InternalServiceTokenFilter> registration=new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,InternalServiceTokenFilter internalFilter,
            @Value("${channels.secure-cookie:false}") boolean secureCookie) throws Exception {
        CookieCsrfTokenRepository csrf=CookieCsrfTokenRepository.withHttpOnlyFalse(); csrf.setCookieCustomizer(c->c.sameSite("Lax").secure(secureCookie).path("/"));
        http.addFilterBefore(internalFilter,UsernamePasswordAuthenticationFilter.class)
                .cors(Customizer.withDefaults()).csrf(c->c.csrfTokenRepository(csrf).ignoringRequestMatchers("/internal/**"))
                .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll());
        return http.build();
    }
    @Bean CorsConfigurationSource corsConfigurationSource(@Value("${channels.public-origin}") String origin) {
        CorsConfiguration c=new CorsConfiguration(); c.setAllowedOrigins(List.of(origin)); c.setAllowedMethods(List.of("GET","POST","PATCH","OPTIONS"));
        c.setAllowedHeaders(List.of("Content-Type","X-XSRF-TOKEN","X-Request-Id")); c.setExposedHeaders(List.of("X-Request-Id")); c.setAllowCredentials(true); c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",c); return source;
    }
}
