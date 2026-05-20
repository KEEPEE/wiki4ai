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
 */
@SpringBootTest
@ActiveProfiles("test")
class InitialAdminBootstrapIT {

    /**
     * Override security.enabled to true so InitialAdminBootstrap loads.
     * Set custom admin credentials for testing.
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
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
        // Since the H2 in-memory DB starts empty, it should have created our testadmin.

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
