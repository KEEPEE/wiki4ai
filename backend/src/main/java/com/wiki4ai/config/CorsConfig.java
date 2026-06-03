package com.wiki4ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for allowing frontend access to the API.
 * Enables cross-origin requests from the React/Vite development server and production builds.
 * 
 * Uses CorsProperties for proper YAML list binding instead of @Value with comma-separated defaults.
 * Note: Spring Security's .cors() configuration in SecurityConfig handles OPTIONS preflight,
 * so we only need WebMvcConfigurer here to ensure CORS headers are added to actual responses.
 */
@Configuration
public class CorsConfig {

    private final CorsProperties corsProperties;

    public CorsConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    /**
     * Configure CORS for all API endpoints via WebMvcConfigurer.
     * This ensures CORS headers are added to responses (actual requests).
     * Spring Security's .cors() configuration handles OPTIONS preflight requests.
     */
    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(corsProperties.getAllowedOrigins().toArray(new String[0]))
                        .allowedMethods(corsProperties.getAllowedMethods().toArray(new String[0]))
                        .allowedHeaders(corsProperties.getAllowedHeaders().toArray(new String[0]))
                        .exposedHeaders("Authorization", "Content-Disposition")
                        .allowCredentials(corsProperties.isAllowCredentials())
                        .maxAge(corsProperties.getMaxAge());
            }
        };
    }
}
