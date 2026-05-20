package com.wiki4ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the initial admin user bootstrap.
 * <p>
 * When the database is empty on first startup, an admin user is automatically
 * created using these credentials. Values can be overridden via environment
 * variables or application.yml:
 * <pre>
 * admin.initial.username=admin
 * admin.initial.password=change-me-now
 * </pre>
 */
@ConfigurationProperties(prefix = "admin.initial")
public record InitialAdminProperties(
        String username,
        String password
) {
}
