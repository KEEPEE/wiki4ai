package com.wiki4ai.config;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the first-run documentation seed (WIKI4AI-74), fresh-instance path.
 *
 * <p>Starts the full Spring context on a dedicated empty H2 database: the seeder must
 * create the "wiki4ai" project with all 9 bundled documents (exact titles, slugs and
 * content) in order and copy all 11 screenshots into the image storage under their
 * exact UUID names. A manual re-run afterwards must be a no-op.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FirstRunDocSeederFreshTest {

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // Dedicated in-memory DB so this context is fully isolated from other ITs.
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:w4ai74-fresh;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
        registry.add("upload.dir", () -> uploadDir.toString());
        // The test profile disables the seed by default (see application-test.properties);
        // this IT tests the enabled path, so opt in explicitly.
        registry.add("wiki4ai.seed.enabled", () -> "true");
        // Embedding sidecar intentionally absent — the seed must work without it.
        registry.add("embedding.enabled", () -> "false");
        registry.add("embedding.base-url", () -> "http://127.0.0.1:9");
    }

    static Path uploadDir;

    static {
        try {
            uploadDir = Files.createTempDirectory("w4ai74-seed-upload-fresh");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private FirstRunDocSeeder seeder;

    @Test
    @Order(1)
    @DisplayName("Fresh instance: seed creates the wiki4ai project with correct name, slug and description")
    void seedsProjectOnFreshInstance() {
        assertThat(projectRepository.count()).as("exactly one (seeded) project").isEqualTo(1);

        Project project = projectRepository.findBySlug("wiki4ai").orElse(null);
        assertThat(project).as("project with slug 'wiki4ai' must exist").isNotNull();
        assertThat(project.getName()).isEqualTo("wiki4ai");
        assertThat(project.getDescription())
                .isEqualTo("Complete documentation of the wiki4ai platform — how it works, technical reference, and operations guide");
    }

    @Test
    @Order(2)
    @DisplayName("Fresh instance: all 9 documents exist with exact titles in creation order")
    void seedsAllNineDocumentsInOrder() {
        assertThat(documentRepository.count()).as("exactly 9 seeded documents").isEqualTo(9);

        List<Document> docs = documentRepository.findAll().stream()
                .sorted(Comparator.comparing(Document::getId))
                .toList();

        List<String> expectedTitles = FirstRunDocSeeder.SEED_DOCUMENTS.stream()
                .map(Map.Entry::getValue)
                .toList();
        List<String> actualTitles = docs.stream().map(Document::getTitle).toList();
        assertThat(actualTitles).as("document titles in id (creation) order").isEqualTo(expectedTitles);

        // Slugs are generated from the titles and must be unique within the project.
        List<String> slugs = docs.stream().map(Document::getSlug).toList();
        assertThat(slugs).doesNotHaveDuplicates();
        assertThat(slugs.get(0)).isEqualTo("home");
        assertThat(slugs.get(6)).isEqualTo("webui-guide");
    }

    @Test
    @Order(3)
    @DisplayName("Fresh instance: document content is byte-identical to the bundled resources")
    void documentContentMatchesBundledResources() throws IOException {
        Long projectId = projectRepository.findBySlug("wiki4ai")
                .orElseThrow(() -> new AssertionError("seeded project missing")).getId();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Map.Entry<String, String> entry : FirstRunDocSeeder.SEED_DOCUMENTS) {
            Document doc = documentRepository.findByProjectIdAndTitle(projectId, entry.getValue())
                    .orElseThrow(() -> new AssertionError("seeded document missing: " + entry.getValue()));
            Resource resource = resolver.getResource("classpath:seed/docs/" + entry.getKey());
            String expected = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(doc.getContent()).as("content of '%s'", entry.getValue()).isEqualTo(expected);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Fresh instance: all 11 screenshots are in image storage with exact UUID names and content")
    void seedsAllImagesWithExactNames() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] bundled = resolver.getResources("classpath*:seed/images/*.png");
        List<Resource> images = Arrays.stream(bundled)
                .sorted(Comparator.comparing(Resource::getFilename))
                .toList();
        assertThat(images).as("11 bundled screenshots").hasSize(11);

        Path projectDir = uploadDir.resolve("wiki4ai");
        for (Resource image : images) {
            Path stored = projectDir.resolve(image.getFilename());
            assertThat(stored).as("stored image " + image.getFilename()).exists();
            assertThat(sha256(Files.readAllBytes(stored)))
                    .as("content hash of " + image.getFilename())
                    .isEqualTo(sha256(image.getInputStream().readAllBytes()));
        }

        // No other files in the project storage directory.
        List<String> storedNames = Files.list(projectDir).map(p -> p.getFileName().toString()).sorted().toList();
        assertThat(storedNames).hasSize(11);
    }

    @Test
    @Order(5)
    @DisplayName("Re-run after seed: no-op (project and document counts unchanged)")
    void reRunAfterSeedIsNoOp() {
        long projectsBefore = projectRepository.count();
        long docsBefore = documentRepository.count();

        seeder.runSeedIfFresh(); // instance is no longer fresh -> must skip

        assertThat(projectRepository.count()).isEqualTo(projectsBefore);
        assertThat(documentRepository.count()).isEqualTo(docsBefore);
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
