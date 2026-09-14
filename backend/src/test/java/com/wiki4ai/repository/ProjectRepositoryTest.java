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

    @Test
    @DisplayName("Should find direct children of a parent project (WIKI4AI-29)")
    void shouldFindByParentId() {
        // given
        Project parent = projectRepository.save(Project.builder()
                .name("Parent").slug("parent").build());
        projectRepository.save(Project.builder()
                .name("Child A").slug("child-a").parent(parent).build());
        projectRepository.save(Project.builder()
                .name("Child B").slug("child-b").parent(parent).build());
        projectRepository.save(Project.builder()
                .name("Other Root").slug("other-root").build());

        // when
        List<Project> children = projectRepository.findByParentId(parent.getId());

        // then
        assertThat(children).hasSize(2);
        assertThat(children).extracting(Project::getSlug)
                .containsExactlyInAnyOrder("child-a", "child-b");
    }

    @Test
    @DisplayName("Should report existsByParentId correctly (WIKI4AI-29)")
    void shouldCheckExistsByParentId() {
        // given
        Project parent = projectRepository.save(Project.builder()
                .name("Parent").slug("parent").build());
        Project empty = projectRepository.save(Project.builder()
                .name("Empty").slug("empty").build());

        // when & then
        assertThat(projectRepository.existsByParentId(parent.getId())).isFalse();
        projectRepository.save(Project.builder()
                .name("Child").slug("child").parent(parent).build());
        assertThat(projectRepository.existsByParentId(parent.getId())).isTrue();
        assertThat(projectRepository.existsByParentId(empty.getId())).isFalse();
    }

    @Test
    @DisplayName("Should find only root projects (parent is null) ordered by createdAt desc (WIKI4AI-29)")
    void shouldFindRootProjects() {
        // given
        Project root1 = projectRepository.save(Project.builder()
                .name("Root One").slug("root-one").build());
        try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        projectRepository.save(Project.builder()
                .name("Root Two").slug("root-two").build());
        projectRepository.save(Project.builder()
                .name("Sub").slug("sub").parent(root1).build());

        // when
        List<Project> roots = projectRepository.findByParentIsNullOrderByCreatedAtDesc();

        // then — only the two roots, newest first
        assertThat(roots).hasSize(2);
        assertThat(roots.get(0).getSlug()).isEqualTo("root-two");
        assertThat(roots.get(1).getSlug()).isEqualTo("root-one");
    }

    @Test
    @DisplayName("Deleting a parent should remove child rows via JPA cascade REMOVE (WIKI4AI-29)")
    void deleteShouldCascadeToChildren() {
        // given — addChild keeps both sides of the relationship consistent,
        // which is what the service layer always does
        Project parent = projectRepository.save(Project.builder()
                .name("Parent").slug("parent").build());
        Project child = Project.builder()
                .name("Child").slug("child").build();
        parent.addChild(child);
        projectRepository.save(child);

        // when — JPA cascade REMOVE on the children association deletes the child row too
        projectRepository.delete(parent);
        projectRepository.flush();

        // then
        assertThat(projectRepository.findById(child.getId())).isEmpty();
    }
}
