package com.wiki4ai.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for JWT-based authentication.
 * When security.enabled=false (test profile), all endpoints are permitted without auth.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired(required = false)
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private final CorsProperties corsProperties;
    private final boolean securityEnabled;

    public SecurityConfig(CorsProperties corsProperties,
                          @Value("${security.enabled:true}") boolean securityEnabled) {
        this.corsProperties = corsProperties;
        this.securityEnabled = securityEnabled;
    }

    /**
     * Configure the security filter chain.
     * - Disables CSRF (stateless JWT auth doesn't need it)
     * - Configures CORS using CorsProperties
     * - Permits /api/v1/auth/** without authentication
     * - When security.enabled=true: requires valid JWT token for all other endpoints
     * - When security.enabled=false: permits all requests (test mode)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(request -> {
                org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
                config.setAllowedOrigins(corsProperties.getAllowedOrigins());
                config.setAllowedMethods(corsProperties.getAllowedMethods());
                config.setAllowedHeaders(corsProperties.getAllowedHeaders());
                config.setAllowCredentials(corsProperties.isAllowCredentials());
                config.setMaxAge(corsProperties.getMaxAge());
                return config;
            }))
            .authorizeHttpRequests(auth -> {
                // Permit all authentication endpoints without authentication
                auth.requestMatchers("/api/v1/auth/**").permitAll();
                // Permit health check and actuator endpoints without authentication
                auth.requestMatchers("/api/health", "/actuator/**", "/h2-console/**").permitAll();
                // Permit OPTIONS preflight requests for CORS
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                // Permit public read-only project endpoints (list and get by slug) without authentication
                auth.requestMatchers(HttpMethod.GET, "/api/v1/projects").permitAll();
                auth.requestMatchers(HttpMethod.GET, "/api/v1/projects/**").permitAll();
                // When security is enabled: require valid JWT token for ALL other endpoints
                // When security is disabled (test mode): permit all requests
                if (securityEnabled) {
                    auth.anyRequest().authenticated();
                } else {
                    auth.anyRequest().permitAll();
                }
            })
            // For unauthenticated access to protected endpoints, return 401 instead of 403
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    (request, response, exception) -> {
                        response.setStatus(org.springframework.http.HttpStatus.UNAUTHORIZED.value());
                        response.setContentType("application/json");
                        response.getWriter().write("{\"message\":\"Authentication required\"}");
                    }));

        // Only add JWT filter when security is enabled and filter is available
        if (securityEnabled && jwtAuthenticationFilter != null) {
            http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }
}
