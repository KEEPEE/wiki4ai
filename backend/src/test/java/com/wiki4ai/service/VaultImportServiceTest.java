package com.wiki4ai.service;

import com.wiki4ai.dto.VaultEntryImportDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class VaultImportServiceTest {

    @Autowired
    private VaultImportService vaultImportService;

    private static final String TEST_PASSWORD = "testpass12345678";

    @Test
    @DisplayName("Should import all entries from KDBX file")
    void shouldImportAllEntries() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            assertThat(entries).hasSize(4);
        }
    }

    @Test
    @DisplayName("Should correctly map entry fields")
    void shouldMapEntryFields() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            VaultEntryImportDTO entry1 = entries.stream()
                    .filter(e -> "Test Entry 1".equals(e.getTitle()))
                    .findFirst()
                    .orElseThrow();

            assertThat(entry1.getUsername()).isEqualTo("user1@example.com");
            assertThat(entry1.getPassword()).isEqualTo("secret123");
            assertThat(entry1.getUrl()).isEqualTo("https://example.com");
            assertThat(entry1.getNotes()).isEqualTo("Some notes here");
        }
    }

    @Test
    @DisplayName("Should set group path for entries in groups")
    void shouldSetGroupPath() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            VaultEntryImportDTO workEntry = entries.stream()
                    .filter(e -> "Work Account".equals(e.getTitle()))
                    .findFirst()
                    .orElseThrow();

            assertThat(workEntry.getGroupPath()).isEqualTo("Root/Work");
        }
    }

    @Test
    @DisplayName("Should set nested group path correctly")
    void shouldSetNestedGroupPath() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            VaultEntryImportDTO nestedEntry = entries.stream()
                    .filter(e -> "Jira Login".equals(e.getTitle()))
                    .findFirst()
                    .orElseThrow();

            assertThat(nestedEntry.getGroupPath()).isEqualTo("Root/Work/Internal Tools");
        }
    }

    @Test
    @DisplayName("Should handle entries with missing optional fields")
    void shouldHandleMissingOptionalFields() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            VaultEntryImportDTO minimalEntry = entries.stream()
                    .filter(e -> "Minimal Entry".equals(e.getTitle()))
                    .findFirst()
                    .orElseThrow();

            assertThat(minimalEntry.getPassword()).isEqualTo("onlypassword");
            assertThat(minimalEntry.getUsername()).isEmpty();
            assertThat(minimalEntry.getUrl()).isEmpty();
            assertThat(minimalEntry.getNotes()).isEmpty();
        }
    }

    @Test
    @DisplayName("Should throw on invalid password")
    void shouldThrowOnInvalidPassword() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            assertThatThrownBy(() -> vaultImportService.importFromKdbx(inputStream, "wrong-password"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid password");
        }
    }

    @Test
    @DisplayName("Should recursively read all entries from nested groups")
    void shouldRecursivelyReadAllEntries() throws IOException {
        try (var inputStream = new ClassPathResource("test-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, TEST_PASSWORD);

            List<String> titles = entries.stream().map(VaultEntryImportDTO::getTitle).toList();

            assertThat(titles).containsExactlyInAnyOrder(
                    "Test Entry 1",
                    "Work Account",
                    "Jira Login",
                    "Minimal Entry"
            );
        }
    }
}
