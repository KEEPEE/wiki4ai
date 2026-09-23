package com.wiki4ai.config;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.service.EmbeddingService;
import com.wiki4ai.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * First-run documentation seed (WIKI4AI-74).
 *
 * <p>Makes "clone the repo → run" a first-class onboarding experience: on a
 * <b>completely fresh instance</b> (users table empty AND projects table empty)
 * the backend seeds the bundled {@code wiki4ai} documentation project — 9 English
 * documents plus the 11 screenshots referenced by the WebUI Guide — so every new
 * installation ships with full product documentation out of the box.</p>
 *
 * <p><b>Non-invasive by construction:</b> the freshness condition (both tables
 * empty) can only ever be true before any user or project exists. After the seed
 * runs, the projects table is no longer empty; after the first account is created,
 * the users table is no longer empty — so the seed executes exactly once per
 * instance and never touches existing data.</p>
 *
 * <p><b>Bundled content:</b> markdown under {@code classpath:seed/docs/} (filenames
 * carry the creation-order prefix) and screenshots under {@code classpath:seed/images/}
 * (exact {uuid}.png names as referenced by the markdown). The images are copied into
 * the project's image storage directory under those same names, so no markdown
 * rewriting is needed ({@link ImageStorageService#storeWithFixedName}).</p>
 *
 * <p><b>Embeddings:</b> seeded documents go through the existing guarded embedding
 * path ({@link EmbeddingService#embedAndSave}, WIKI4AI-35) — they are embedded when
 * the sidecar is reachable and simply stay text-searchable (with a logged warning)
 * when it is not; the admin backfill endpoint fills any gap later. The seed itself
 * therefore works with or without the embedding sidecar.</p>
 *
 * <p><b>Opt-out:</b> {@code wiki4ai.seed.enabled} (env {@code WIKI4AI_SEED_ENABLED}),
 * default {@code true}. When disabled the bean is not created at all.</p>
 *
 * <p><b>Failure behaviour:</b> a seed failure on a fresh instance is logged as ERROR
 * (with stack trace) and does NOT crash startup — the DB transaction rolls back, both
 * tables stay empty, and the seed retries on the next restart. Images already copied
 * to the upload dir are overwritten idempotently on retry.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "wiki4ai.seed.enabled", havingValue = "true", matchIfMissing = true)
public class FirstRunDocSeeder {

    public static final String PROJECT_NAME = "wiki4ai";
    public static final String PROJECT_SLUG = "wiki4ai";
    /** Exact description of the source project on dev .219 (single source of truth at export time). */
    public static final String PROJECT_DESCRIPTION =
            "Complete documentation of the wiki4ai platform — how it works, technical reference, and operations guide";

    private static final String DOCS_CLASSPATH_DIR = "seed/docs/";
    private static final String IMAGES_CLASSPATH_PATTERN = "classpath*:seed/images/*";

    /**
     * Seed documents in creation order: (classpath resource filename, exact title).
     * Titles must match the source project exactly — they cannot be derived from the
     * markdown H1 (e.g. Home's heading is "Home — wiki4ai Documentation" but its
     * document title is "Home"), and [[WikiLink]] resolution in the WebUI matches on
     * document titles.
     */
    static final List<Map.Entry<String, String>> SEED_DOCUMENTS = List.of(
            Map.entry("01-home.md", "Home"),
            Map.entry("02-getting-started.md", "Getting Started"),
            Map.entry("03-architecture-overview.md", "Architecture Overview"),
            Map.entry("04-backend-api-reference.md", "Backend API Reference"),
            Map.entry("05-data-model.md", "Data Model"),
            Map.entry("06-search-embeddings.md", "Search & Embeddings"),
            Map.entry("07-webui-guide.md", "WebUI Guide"),
            Map.entry("08-mcp-server.md", "MCP Server"),
            Map.entry("09-deployment-operations.md", "Deployment & Operations")
    );

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final ImageStorageService imageStorageService;
    private final EmbeddingService embeddingService;
    private final TransactionTemplate transactionTemplate;

    /**
     * ApplicationReadyEvent hook: the seed runs only after the full context (and the
     * schema) is up, and never before any HTTP request can be served. Failures are
     * logged but do not crash startup — see class javadoc for the retry semantics.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            runSeedIfFresh();
        } catch (Exception e) {
            log.error("First-run doc seed FAILED — the instance starts without the documentation project. "
                    + "The database is still fresh, so the seed will retry on the next restart.", e);
        }
    }

    /**
     * Seed only when the instance is completely fresh: no users AND no projects.
     * Otherwise logs a single INFO line and does nothing (idempotent by construction).
     */
    public void runSeedIfFresh() {
        long users = userRepository.count();
        long projects = projectRepository.count();
        if (users > 0 || projects > 0) {
            log.info("First-run doc seed skipped (instance not fresh): users={}, projects={}", users, projects);
            return;
        }
        seed();
    }

    /**
     * Create the documentation project with all bundled documents and images.
     * Intended for a fresh instance only — callers must check freshness first
     * (see {@link #runSeedIfFresh()}).
     */
    void seed() {
        List<Resource> imageResources = loadImageResources();
        log.info("Seeding first-run documentation project '{}' ({} documents, {} images)...",
                PROJECT_NAME, SEED_DOCUMENTS.size(), imageResources.size());

        // 1) Images first (filesystem, outside the DB transaction): if the DB step
        //    below fails and rolls back, orphan files on a fresh instance are
        //    harmless — nothing references them yet, and a retry overwrites them.
        for (Resource image : imageResources) {
            try {
                byte[] data = image.getInputStream().readAllBytes();
                imageStorageService.storeWithFixedName(PROJECT_SLUG, image.getFilename(), data);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to seed image " + image.getFilename(), e);
            }
        }

        // 2) Project + all documents in ONE transaction — the seed is atomic: either
        //    the whole documentation project exists or none of it does.
        List<Document> savedDocs = transactionTemplate.execute(status -> {
            Project project = Project.builder()
                    .name(PROJECT_NAME)
                    .description(PROJECT_DESCRIPTION)
                    .build();
            projectRepository.save(project);

            List<Document> docs = new ArrayList<>();
            for (Map.Entry<String, String> entry : SEED_DOCUMENTS) {
                Document doc = Document.builder()
                        .title(entry.getValue())
                        .content(readResource(DOCS_CLASSPATH_DIR + entry.getKey()))
                        .project(project)
                        .build();
                docs.add(documentRepository.save(doc));
            }
            return docs;
        });

        // 3) Embeddings via the existing guarded path (WIKI4AI-35): embedAndSave never
        //    throws for sidecar failures — documents stay text-searchable and the admin
        //    backfill endpoint fills the gap when the sidecar is (re)available. This is
        //    what makes the seed work on instances without the embedding sidecar.
        for (Document doc : savedDocs) {
            embeddingService.embedAndSave(doc);
        }

        log.info("First-run documentation project '{}' seeded: {} documents, {} images",
                PROJECT_NAME, SEED_DOCUMENTS.size(), imageResources.size());
    }

    /** Read a bundled markdown document as UTF-8 text; fails loudly when missing. */
    private String readResource(String classpathPath) {
        Resource resource = new PathMatchingResourcePatternResolver().getResource("classpath:" + classpathPath);
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing first-run seed resource: " + classpathPath, e);
        }
    }

    /** Enumerate the bundled screenshots (sorted for deterministic order). */
    private List<Resource> loadImageResources() {
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(IMAGES_CLASSPATH_PATTERN);
            List<Resource> images = Arrays.stream(resources)
                    .filter(r -> r.getFilename() != null && r.getFilename().endsWith(".png"))
                    .sorted(Comparator.comparing(Resource::getFilename))
                    .toList();
            if (images.isEmpty()) {
                throw new IllegalStateException("No seed images found on the classpath (seed/images/) — "
                        + "the WebUI Guide references 11 screenshots and would render broken");
            }
            return images;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to enumerate first-run seed images", e);
        }
    }
}
