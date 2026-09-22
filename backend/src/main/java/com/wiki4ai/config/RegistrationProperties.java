package com.wiki4ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the public self-registration policy (WIKI4AI-70).
 * <p>
 * Property: {@code auth.registration.open} (tri-state):
 * <ul>
 *   <li><b>unset / empty (default)</b> — registration is open only while the users
 *       table is empty (first-run), and closes automatically once the first account
 *       exists. This is the secure default: a fresh instance cannot be filled with
 *       anonymous accounts after its first admin is created.</li>
 *   <li><b>{@code true}</b> — registration stays open permanently. Opt-in for
 *       self-hosters who deliberately want public sign-up on their instance.</li>
 *   <li><b>{@code false}</b> — registration is never open; accounts are created via
 *       the first-run setup flow (empty DB) or by an admin via /admin/users.</li>
 * </ul>
 * The value can be set via environment variable {@code AUTH_REGISTRATION_OPEN}
 * or in application.yml. Invalid values fail fast at startup (strict Boolean binding).
 */
@ConfigurationProperties(prefix = "auth.registration")
public record RegistrationProperties(
        /** Tri-state registration policy; null = default (open only while no user exists). */
        Boolean open
) {
}
