package com.wiki4ai.testguard.fixtures;

import com.wiki4ai.model.Project;
import com.wiki4ai.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixture: writing rows is NOT an excuse for a fixed total on a shared table. */
@SpringBootTest
class WritesButDoesNotEmptyFixture {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void savesTwoAndAssertsTwo() {
        projectRepository.save(Project.builder().name("A").slug("a").build());
        projectRepository.save(Project.builder().name("B").slug("b").build());
        assertThat(projectRepository.count()).isEqualTo(2);
    }
}
