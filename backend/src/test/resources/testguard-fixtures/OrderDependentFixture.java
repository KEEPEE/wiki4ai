package com.wiki4ai.testguard.fixtures;

import com.wiki4ai.repository.ProjectRepository;
import com.wiki4ai.repository.UserRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixture: the forbidden shape. Three fixed totals, nothing that establishes them. */
@SpringBootTest
@ActiveProfiles("test")
class OrderDependentFixture {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void claimsTheUsersTableIsEmpty() {
        assertThat(userRepository.count()).isZero();
    }

    @Test
    void claimsExactlyOneProject() {
        assertThat(projectRepository.count()).as("only one project").isEqualTo(1);
    }

    @Nested
    class NestedClaimsZeroWithoutCleaning {
        @Test
        void nestedClaim() {
            assertThat(userRepository.count()).isZero();
        }
    }
}
