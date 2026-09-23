package com.wiki4ai.config;

import com.wiki4ai.model.Role;
import com.wiki4ai.model.User;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the first-run documentation seed (WIKI4AI-74), non-fresh path:
 * a user already exists before startup completes → the seeder must be a complete no-op
 * (no project, no documents, nothing written to image storage).
 */
@SpringBootTest
@ActiveProfiles("test")
class FirstRunDocSeederNonFreshUserTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai74-user;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        registry.add("upload.dir", () -> uploadDir.toString());
        // Test profile disables the seed by default; this IT tests the enabled path.
        registry.add("wiki4ai.seed.enabled", () -> "true");
        registry.add("embedding.enabled", () -> "false");
        registry.add("embedding.base-url", () -> "http://127.0.0.1:9");
    }

    static Path uploadDir;

    static {
        try {
            uploadDir = Files.createTempDirectory("w4ai74-seed-upload-user");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * CommandLineRunners execute during context startup, BEFORE the
     * ApplicationReadyEvent that triggers the seeder — so this user exists when the
     * freshness check runs.
     */
    @TestConfiguration
    static class PreExistingUser {
        @Bean
        CommandLineRunner createExistingUser(UserRepository userRepository) {
            return args -> userRepository.save(User.builder()
                    .username("existing-user")
                    .email("existing@example.com")
                    .password("not-a-real-password")
                    .role(Role.USER)
                    .build());
        }
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    @DisplayName("Non-fresh instance (user exists): seeder creates nothing")
    void doesNotSeedWhenUserExists() {
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(projectRepository.count()).as("no project seeded when a user already exists").isZero();
        assertThat(documentRepository.count()).as("no documents seeded when a user already exists").isZero();
        assertThat(projectRepository.findBySlug("wiki4ai")).isEmpty();

        // Nothing may have been written to the image storage either.
        assertThat(Files.exists(uploadDir.resolve("wiki4ai")))
                .as("no image storage directory for the seed project").isFalse();
    }
}
