package com.wiki4ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;
import java.util.List;

/**
 * CORS properties bound from application.yml.
 * Uses @ConfigurationProperties for proper YAML list binding.
 */
@Data
@Component
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of(
        "http://localhost:5173",
        "http://localhost:3000",
        "http://localhost:80",
        "http://192.168.77.177:3000",
        "http://192.168.77.177",
        "http://localhost"
    );

    private List<String> allowedMethods = List.of(
        "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
    );

    private List<String> allowedHeaders = List.of(
        "Authorization",
        "Content-Type",
        "X-Requested-With",
        "Accept",
        "Origin",
        "Referer",
        "Access-Control-Request-Method",
        "Access-Control-Request-Headers"
    );

    private boolean allowCredentials = true;

    private long maxAge = 3600;
}
