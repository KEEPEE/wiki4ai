package com.wiki4ai.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Role enum.
 */
class RoleEnumTest {

    @Test
    @DisplayName("should have exactly two values: USER and ADMIN")
    void shouldHaveTwoValues() {
        assertThat(Role.values()).hasSize(2);
        assertThat(Role.values()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    @DisplayName("USER value name should be 'USER'")
    void userValueNameShouldBeUser() {
        assertThat(Role.USER.name()).isEqualTo("USER");
    }

    @Test
    @DisplayName("ADMIN value name should be 'ADMIN'")
    void adminValueNameShouldBeAdmin() {
        assertThat(Role.ADMIN.name()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("should parse string 'USER' to Role.USER")
    void shouldParseUserString() {
        assertThat(Role.valueOf("USER")).isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("should parse string 'ADMIN' to Role.ADMIN")
    void shouldParseAdminString() {
        assertThat(Role.valueOf("ADMIN")).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("should throw IllegalArgumentException for invalid role string")
    void shouldThrowForInvalidRoleString() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Role.valueOf("INVALID"));
    }
}
