package com.wiki4ai.repository;

import com.wiki4ai.model.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Data JPA tests for ProjectRepository.
 */
@DataJpaTest
@ActiveProfiles("test")
class ProjectRepositoryTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    @DisplayName("Should find project by slug")
    void shouldFindBySlug() {
        // given
        Project project = Project.builder()
                .name("Test Project")
                .description("A test project")
                .slug("test-project")
                .build();
        projectRepository.save(project);

        // when
        Optional<Project> found = projectRepository.findBySlug("test-project");

        // then
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test Project");
    }

    @Test
    @DisplayName("Should return empty when slug not found")
    void shouldReturnEmptyWhenSlugNotFound() {
        // when
        Optional<Project> found = projectRepository.findBySlug("non-existent");

        // then
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("Should check if project exists by name")
    void shouldCheckExistsByName() {
        // given
        projectRepository.save(Project.builder()
                .name("Existing Project")
                .slug("existing-project")
                .build());

        // when & then
        assertThat(projectRepository.existsByName("Existing Project")).isTrue();
        assertThat(projectRepository.existsByName("Non Existing")).isFalse();
    }

    @Test
    @DisplayName("Should check if project exists by slug")
    void shouldCheckExistsBySlug() {
        // given
        projectRepository.save(Project.builder()
                .name("Existing Project")
                .slug("existing-slug")
                .build());

        // when & then
        assertThat(projectRepository.existsBySlug("existing-slug")).isTrue();
        assertThat(projectRepository.existsBySlug("non-existing-slug")).isFalse();
    }

    @Test
    @DisplayName("Should find all projects ordered by creation date descending")
    void shouldFindAllOrderByCreatedAtDesc() {
        // given
        Project older = Project.builder()
                .name("Older Project")
                .slug("older-project")
                .build();
        Project newer = Project.builder()
                .name("Newer Project")
                .slug("newer-project")
                .build();

        projectRepository.save(older);
        // Small delay to ensure different timestamps
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        projectRepository.save(newer);

        // when
        List<Project> projects = projectRepository.findAllByOrderByCreatedAtDesc();

        // then
        assertThat(projects).hasSize(2);
        assertThat(projects.get(0).getName()).isEqualTo("Newer Project");
        assertThat(projects.get(1).getName()).isEqualTo("Older Project");
    }

    @Test
    @DisplayName("Should save and retrieve project with timestamps")
    void shouldSaveAndRetrieveWithTimestamps() {
        // given
        Project project = Project.builder()
                .name("Timestamped Project")
                .slug("timestamped-project")
                .description("Project with timestamps")
                .build();

        // when
        Project saved = projectRepository.save(project);

        // then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(LocalDateTime.now().isAfter(saved.getCreatedAt())).isTrue();
    }

    @Test
    @DisplayName("Should count all projects")
    void shouldCountAllProjects() {
        // given
        projectRepository.save(Project.builder().name("P1").slug("p1").build());
        projectRepository.save(Project.builder().name("P2").slug("p2").build());

        // when & then
        assertThat(projectRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should delete project by id")
    void shouldDeleteProjectById() {
        // given
        Project saved = projectRepository.save(Project.builder()
                .name("To Delete")
                .slug("to-delete")
                .build());
        Long id = saved.getId();

        // when
        projectRepository.deleteById(id);

        // then
        assertThat(projectRepository.findById(id)).isEmpty();
    }
}
