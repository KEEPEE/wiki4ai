package com.wiki4ai.config;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for InitialAdminBootstrap with full Spring context.
 * <p>
 * This test starts the complete application context with security enabled,
 * an in-memory H2 database, and verifies that the admin user is created
 * automatically on first startup when the database is empty.
 * <p>
 * <b>WIKI4AI-169 — why this class owns its own database.</b> Every other Spring test
 * context in this suite points at the shared named H2 {@code jdbc:h2:mem:testdb}, and
 * contexts are cached for the life of the JVM. That made both claims here order-dependent:
 * the bootstrap only provisions the admin when the users table is empty, so a class that
 * ran earlier and left an account behind would (a) suppress {@code testadmin} entirely and
 * (b) make {@code userRepository.count() == 1} false. Which class ran first is a Surefire
 * {@code runOrder} question, and {@code runOrder} defaults to {@code filesystem}.
 * <p>
 * The fix is isolation, not cleanup: this class needs the table to start empty, and
 * deleting other classes' rows to get that would break them instead. A private named
 * database makes "the users table is empty" a fact about this class alone. Same pattern as
 * the {@code FirstRunDocSeeder*Test} classes. Not {@code @DirtiesContext} — that would drop
 * the schema the other cached contexts are still using.
 */
@SpringBootTest
@ActiveProfiles("test")
class InitialAdminBootstrapIT {

    /**
     * Override security.enabled to true so InitialAdminBootstrap loads.
     * Set custom admin credentials for testing.
     * <p>
     * The datasource is this class's own in-memory H2 (WIKI4AI-169): the bootstrap under
     * test is triggered by an empty database, so it cannot share one with the rest of the
     * suite.
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai169-initialadmin;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        registry.add("security.enabled", () -> "true");
        registry.add("admin.initial.username", () -> "testadmin");
        registry.add("admin.initial.password", () -> "test-password-123");
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Should create admin user on startup when database is empty")
    void shouldCreateAdminOnStartup() {
        // The InitialAdminBootstrap CommandLineRunner runs during context startup.
        // This class owns its H2 (see the class javadoc), so the database this context
        // starts against is empty no matter which test class Surefire ran before it.

        User admin = userRepository.findByUsername("testadmin").orElse(null);

        assertThat(admin).as("admin user should exist after bootstrap").isNotNull();
        assertThat(admin.getUsername()).isEqualTo("testadmin");
        assertThat(admin.getEmail()).isEqualTo("testadmin@localhost");
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("Admin password should be BCrypt hashed and verifiable")
    void adminPasswordShouldBeHashedAndVerifiable() {
        User admin = userRepository.findByUsername("testadmin").orElse(null);

        assertThat(admin).isNotNull();
        assertThat(admin.getPassword()).as("password should be BCrypt hash").startsWith("$2a$");
        assertThat(admin.getPassword()).as("password must not be plaintext")
                .isNotEqualTo("test-password-123");

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        assertThat(encoder.matches("test-password-123", admin.getPassword()))
                .as("raw password should match the stored hash").isTrue();
    }

    @Test
    @DisplayName("Only one user (the admin) should exist after bootstrap")
    void onlyAdminShouldExist() {
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
