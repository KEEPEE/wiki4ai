package com.wiki4ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for JWT-based authentication.
 * Only active when security.enabled=true (default). Disabled in test contexts.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(name = "security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, CorsProperties corsProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsProperties = corsProperties;
    }

    /**
     * Configure the security filter chain.
     * - Disables CSRF (stateless JWT auth doesn't need it)
     * - Configures CORS using CorsProperties
     * - Permits /api/v1/auth/** without authentication
     * - Requires valid JWT token for all other endpoints
     * - Adds JwtAuthenticationFilter before UsernamePasswordAuthenticationFilter
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
            .authorizeHttpRequests(auth -> auth
                // Permit all authentication endpoints without authentication
                .requestMatchers("/api/v1/auth/**").permitAll()
                // Permit OPTIONS preflight requests for CORS
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // Require valid JWT token for ALL other endpoints
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
