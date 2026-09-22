package com.wiki4ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the initial admin user bootstrap.
 * <p>
 * WIKI4AI-69: strictly opt-in — there are NO defaults. When the database is empty
 * on startup AND both values are explicitly set, an admin user is created from
 * them (compose-style non-interactive provisioning). When either value is unset,
 * the bootstrap skips and the instance starts uninitialized; the first ADMIN
 * account is then created through the WebUI first-run setup flow. This removes
 * the old publicly-known {@code admin/change-me-now} default credentials.
 * <p>
 * Values are provided via environment variables or application.yml:
 * <pre>
 * ADMIN_INITIAL_USERNAME=myadmin   # -> admin.initial.username
 * ADMIN_INITIAL_PASSWORD=...       # -> admin.initial.password (secret)
 * </pre>
 */
@ConfigurationProperties(prefix = "admin.initial")
public record InitialAdminProperties(
        /** Explicitly provisioned admin username; null/blank = bootstrap disabled. */
        String username,
        /** Explicitly provisioned admin password; null/blank = bootstrap disabled. Never logged. */
        String password
) {
}
