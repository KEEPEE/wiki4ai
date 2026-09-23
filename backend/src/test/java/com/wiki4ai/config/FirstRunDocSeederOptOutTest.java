package com.wiki4ai.config;

import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the first-run documentation seed (WIKI4AI-74), opt-out path:
 * with {@code wiki4ai.seed.enabled=false} (env WIKI4AI_SEED_ENABLED) the seeder bean is
 * not created at all and a fresh instance stays completely empty.
 */
@SpringBootTest
@ActiveProfiles("test")
class FirstRunDocSeederOptOutTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai74-optout;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        registry.add("upload.dir", () -> uploadDir.toString());
        registry.add("embedding.enabled", () -> "false");
        registry.add("embedding.base-url", () -> "http://127.0.0.1:9");
        // The opt-out under test (property form of env WIKI4AI_SEED_ENABLED).
        registry.add("wiki4ai.seed.enabled", () -> "false");
    }

    static Path uploadDir;

    static {
        try {
            uploadDir = Files.createTempDirectory("w4ai74-seed-upload-optout");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Opt-out: seeder bean is not created and nothing is seeded on a fresh instance")
    void optOutDisablesSeederEntirely() {
        assertThat(applicationContext.getBeanProvider(FirstRunDocSeeder.class).getIfAvailable())
                .as("FirstRunDocSeeder must not be a bean when wiki4ai.seed.enabled=false").isNull();

        // The instance is completely fresh (no users, no projects) yet the seed must NOT run.
        assertThat(userRepository.count()).isZero();
        assertThat(projectRepository.count()).as("opt-out: no project seeded").isZero();
        assertThat(Files.exists(uploadDir.resolve("wiki4ai")))
                .as("opt-out: no image storage written").isFalse();
    }
}
