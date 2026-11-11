package com.wiki4ai.service;

import com.wiki4ai.dto.DocumentCreateDTO;
import com.wiki4ai.dto.ProjectCreateDTO;
import com.wiki4ai.dto.ProjectDTO;
import com.wiki4ai.dto.ProjectTreeNodeDTO;
import com.wiki4ai.model.Project;
import com.wiki4ai.repository.DocumentRepository;
import com.wiki4ai.repository.ProjectPermissionRepository;
import com.wiki4ai.repository.ProjectRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-197 commit 16: the project list and the project tree must not ask a question
 * per project.
 *
 * <h2>The bug</h2>
 * The dashboard renders one card per project and each card shows a document count. That
 * count came from {@code project.getDocuments().size()} — a lazy {@code @OneToMany} — so
 * listing N projects issued N selects, each loading <b>every document row</b> of that
 * project (title, content, embedding) and keeping only the size. The tree had the same
 * shape with an explicit {@code countByProjectId} per node.
 *
 * <h2>How this asserts it without a stopwatch</h2>
 * Hibernate's own statistics counter for prepared statements. The assertion is a
 * <i>delta</i>: after adding more projects and their documents, the same call must still
 * cost exactly the same number of statements. A per-project query would grow the count
 * with the number of projects, which is what an N+1 is; a batched query does not.
 *
 * <p>{@code generate_statistics=true} is set per-class so the flag does not leak into the
 * contexts every other test shares. It costs a little overhead in this class only.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "hibernate.generate_statistics=true")
class ProjectListQueryCountTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ProjectPermissionRepository projectPermissionRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @BeforeEach
    @AfterEach
    void leaveNothingBehind() {
        documentRepository.deleteAll();
        projectPermissionRepository.deleteAll();
        projectRepository.deleteAll();
    }

    private void createProjectWithDocs(String name, int docCount) {
        ProjectDTO project = projectService.createProject(
                ProjectCreateDTO.builder().name(name).description("d").build(), null);
        for (int i = 1; i <= docCount; i++) {
            documentService.createDocument(project.getId(),
                    DocumentCreateDTO.builder().title(name + " doc " + i).content("body").build(), null);
        }
    }

    /** Statements prepared by one call, with the counters reset immediately before it. */
    private long statementsOf(Runnable call) {
        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        call.run();
        return (long) stats.getPrepareStatementCount();
    }

    @Test
    @DisplayName("the project list costs the same number of statements for 4 as for 8 projects")
    void projectListDoesNotScaleWithProjectCount() {
        for (int i = 1; i <= 4; i++) {
            createProjectWithDocs("Small Wiki " + i, 3);
        }

        long withFour = statementsOf(() -> {
            List<ProjectDTO> list = projectService.getAllProjects(null, null, null, null);
            assertThat(list).hasSize(4);
            assertThat(list).allSatisfy(p -> assertThat(p.getDocumentCount()).isEqualTo(3));
        });

        for (int i = 5; i <= 8; i++) {
            createProjectWithDocs("Small Wiki " + i, 3);
        }

        long withEight = statementsOf(() -> {
            List<ProjectDTO> list = projectService.getAllProjects(null, null, null, null);
            assertThat(list).hasSize(8);
            assertThat(list).allSatisfy(p -> assertThat(p.getDocumentCount()).isEqualTo(3));
        });

        assertThat(withEight)
                .as("doubling the projects must not add a single statement — one list query "
                        + "plus one grouped count is the whole cost")
                .isEqualTo(withFour);
    }

    @Test
    @DisplayName("the project list initializes no lazy collection")
    void projectListInitializesNoCollections() {
        for (int i = 1; i <= 3; i++) {
            createProjectWithDocs("No Init Wiki " + i, 4);
        }

        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        List<ProjectDTO> list = projectService.getAllProjects(null, null, null, null);

        assertThat(list).hasSize(3);
        assertThat(stats.getCollectionLoadCount())
                .as("getDocuments() must not be touched to produce a count — that is the "
                        + "collection load this commit removes")
                .isZero();
    }

    @Test
    @DisplayName("the counts the dashboard shows are the real ones")
    void batchedCountsMatchTheRealNumbers() {
        createProjectWithDocs("Empty Project", 0);
        createProjectWithDocs("One Doc Project", 1);
        createProjectWithDocs("Five Doc Project", 5);

        List<ProjectDTO> list = projectService.getAllProjects(null, null, null, null);

        assertThat(list).extracting(ProjectDTO::getName, ProjectDTO::getDocumentCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Empty Project", 0),
                        org.assertj.core.groups.Tuple.tuple("One Doc Project", 1),
                        org.assertj.core.groups.Tuple.tuple("Five Doc Project", 5));
    }

    @Test
    @DisplayName("the tree carries the same counts and does not ask per node")
    void projectTreeCountsAreBatched() {
        ProjectDTO root = projectService.createProject(
                ProjectCreateDTO.builder().name("Tree Root").build(), null);
        for (int i = 1; i <= 3; i++) {
            ProjectCreateDTO child = ProjectCreateDTO.builder()
                    .name("Tree Child " + i).parentId(root.getId()).build();
            ProjectDTO childDto = projectService.createProject(child, null);
            for (int d = 1; d <= i; d++) {
                documentService.createDocument(childDto.getId(),
                        DocumentCreateDTO.builder().title("Doc " + d).content("body").build(), null);
            }
        }
        String rootSlug = projectRepository.findById(root.getId()).orElseThrow().getSlug();

        long statements = statementsOf(() -> {
            ProjectTreeNodeDTO tree = projectService.getProjectTree(rootSlug, null);
            assertThat(tree.getChildren()).hasSize(3);
            assertThat(tree.getChildren()).extracting(ProjectTreeNodeDTO::getDocumentCount)
                    .containsExactlyInAnyOrder(1, 2, 3);
        });

        // One query for the root, one for the whole hierarchy, one grouped count for all
        // nodes. Anything per node would push this above three.
        assertThat(statements)
                .as("the tree must cost a fixed number of statements, not one per node")
                .isLessThanOrEqualTo(3L);
    }

    @Test
    @DisplayName("a filtered list counts only what it returns")
    void filteredListStillReportsCorrectCounts() {
        createProjectWithDocs("Alpha Keep", 2);
        createProjectWithDocs("Beta Drop", 7);

        List<ProjectDTO> filtered = projectService.getAllProjects(null, "Alpha", null, null);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).getDocumentCount()).isEqualTo(2);
    }

    /**
     * The single-project path keeps working after the batch was introduced: it now counts
     * with a COUNT query instead of loading the collection, which is the same number and
     * strictly less I/O.
     */
    @Test
    @DisplayName("a single project read reports its document count without loading documents")
    void singleProjectReadCountsWithoutCollectionLoad() {
        createProjectWithDocs("Single Read", 4);
        String slug = projectRepository.findAll().stream()
                .map(Project::getSlug).collect(Collectors.toList()).get(0);

        Statistics stats = statistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        ProjectDTO project = projectService.getProjectBySlug(slug, null);

        assertThat(project.getDocumentCount()).isEqualTo(4);
        assertThat(stats.getCollectionLoadCount()).isZero();
    }
}
