package com.wiki4ai.config;

import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
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
 * a project already exists before startup completes → the seeder must be a complete no-op,
 * even though the users table is empty.
 */
@SpringBootTest
@ActiveProfiles("test")
class FirstRunDocSeederNonFreshProjectTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai74-project;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        registry.add("upload.dir", () -> uploadDir.toString());
        // Test profile disables the seed by default; this IT tests the enabled path.
        registry.add("wiki4ai.seed.enabled", () -> "true");
        registry.add("embedding.enabled", () -> "false");
        registry.add("embedding.base-url", () -> "http://127.0.0.1:9");
    }

    static Path uploadDir;

    static {
        try {
            uploadDir = Files.createTempDirectory("w4ai74-seed-upload-project");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** CommandLineRunners run before the ApplicationReadyEvent that triggers the seeder. */
    @TestConfiguration
    static class PreExistingProject {
        @Bean
        CommandLineRunner createExistingProject(ProjectRepository projectRepository) {
            return args -> projectRepository.save(Project.builder()
                    .name("other-project")
                    .description("a pre-existing project that makes the instance non-fresh")
                    .build());
        }
    }

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    @DisplayName("Non-fresh instance (project exists): seeder creates nothing, existing project untouched")
    void doesNotSeedWhenProjectExists() {
        assertThat(projectRepository.count()).as("only the pre-existing project").isEqualTo(1);
        assertThat(projectRepository.findBySlug("other-project")).isPresent();
        assertThat(projectRepository.findBySlug("wiki4ai"))
                .as("the seed project must not be created when any project already exists").isEmpty();
        assertThat(documentRepository.count()).isZero();

        assertThat(Files.exists(uploadDir.resolve("wiki4ai")))
                .as("no image storage directory for the seed project").isFalse();
    }
}
