package com.wiki4ai.testguard.fixtures;

import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import com.wiki4ai.repository.WorkItemCommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixture: the three accepted ways of establishing a fixed total. */
@SpringBootTest
@ActiveProfiles("test")
class OwnDatabaseFixture {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:fixture-own-db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false");
    }

    @Autowired
    private UserRepository userRepository;

    @Test
    void emptyIsAFactAboutThisClasssOwnDatabase() {
        assertThat(userRepository.count()).isZero();
    }
}

@DataJpaTest
class IsolatedSliceFixture {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void privateEmbeddedDatabaseAndRollback() {
        projectRepository.save(Project.builder().name("A").slug("a").build());
        projectRepository.save(Project.builder().name("B").slug("b").build());
        assertThat(projectRepository.count()).isEqualTo(2);
    }
}

@SpringBootTest
class SelfEmptiedFixture {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkItemCommentRepository workItemCommentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanState();
    }

    private void cleanState() {
        userRepository.deleteAll();
        projectRepository.deleteAll();
        jdbcTemplate.execute("DELETE FROM work_item_comments");
    }

    @Test
    void zeroAfterOwnClear() {
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void oneAfterOwnClearAndOwnWrite() {
        projectRepository.save(Project.builder().name("C").slug("c").build());
        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void appendOnlyTableEmptiedWithNativeSql() {
        assertThat(workItemCommentRepository.count()).isZero();
    }
}
