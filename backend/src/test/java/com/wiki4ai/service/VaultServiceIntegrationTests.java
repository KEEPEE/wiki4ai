package com.wiki4ai.service;

import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for VaultService using real database (H2).
 * Tests CRUD operations and ownership validation.
 */
@SpringBootTest
@ActiveProfiles("test")
class VaultServiceIntegrationTests {

    @Autowired
    private VaultService vaultService;

    private static final Long USER_1_ID = 1L;
    private static final Long USER_2_ID = 2L;

    private EncryptedVaultEntryDTO createTestDTO(String title) {
        return EncryptedVaultEntryDTO.builder()
                .title(title)
                .usernameEncrypted(new byte[]{0x01, 0x02})
                .passwordEncrypted(new byte[]{0x03, 0x04, 0x05})
                .notesEncrypted(new byte[]{0x06})
                .url("https://example.com")
                .groupPath("/work")
                .iv(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD})
                .build();
    }

    @BeforeEach
    void setUp() {
        // Clean up entries for both test users before each test
        List<EncryptedVaultEntryDTO> user1Entries = vaultService.getEntriesByUser(USER_1_ID);
        for (EncryptedVaultEntryDTO dto : user1Entries) {
            try {
                vaultService.deleteEntry(dto.getId(), USER_1_ID);
            } catch (Exception ignored) {}
        }
        List<EncryptedVaultEntryDTO> user2Entries = vaultService.getEntriesByUser(USER_2_ID);
        for (EncryptedVaultEntryDTO dto : user2Entries) {
            try {
                vaultService.deleteEntry(dto.getId(), USER_2_ID);
            } catch (Exception ignored) {}
        }
    }

    @Nested
    @DisplayName("Create Entry Integration Tests")
    class CreateEntryTests {

        @Test
        @DisplayName("Should create a new vault entry for user")
        void shouldCreateEntry() {
            EncryptedVaultEntryDTO dto = createTestDTO("My Secret");

            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, dto);

            assertThat(created).isNotNull();
            assertThat(created.getId()).isNotNull();
            assertThat(created.getTitle()).isEqualTo("My Secret");
            assertThat(created.getUrl()).isEqualTo("https://example.com");
            assertThat(created.getGroupPath()).isEqualTo("/work");
            assertThat(created.getPasswordEncrypted()).isEqualTo(dto.getPasswordEncrypted());
            assertThat(created.getIv()).isEqualTo(dto.getIv());
        }

        @Test
        @DisplayName("Should create entries for different users independently")
        void shouldCreateEntriesForDifferentUsers() {
            EncryptedVaultEntryDTO dto1 = createTestDTO("User 1 Entry");
            EncryptedVaultEntryDTO dto2 = createTestDTO("User 2 Entry");

            vaultService.createEntry(USER_1_ID, dto1);
            vaultService.createEntry(USER_2_ID, dto2);

            List<EncryptedVaultEntryDTO> user1Entries = vaultService.getEntriesByUser(USER_1_ID);
            List<EncryptedVaultEntryDTO> user2Entries = vaultService.getEntriesByUser(USER_2_ID);

            assertThat(user1Entries).hasSize(1);
            assertThat(user1Entries.get(0).getTitle()).isEqualTo("User 1 Entry");
            assertThat(user2Entries).hasSize(1);
            assertThat(user2Entries.get(0).getTitle()).isEqualTo("User 2 Entry");
        }

        @Test
        @DisplayName("Should reject creation with null DTO")
        void shouldRejectNullDTO() {
            assertThatThrownBy(() -> vaultService.createEntry(USER_1_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DTO cannot be null");
        }

        @Test
        @DisplayName("Should reject creation with blank title")
        void shouldRejectBlankTitle() {
            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder()
                    .title("")
                    .passwordEncrypted(new byte[]{1})
                    .iv(new byte[]{2})
                    .build();

            assertThatThrownBy(() -> vaultService.createEntry(USER_1_ID, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Title is required");
        }

        @Test
        @DisplayName("Should reject creation without password encrypted")
        void shouldRejectMissingPasswordEncrypted() {
            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder()
                    .title("No Password")
                    .iv(new byte[]{1})
                    .build();

            assertThatThrownBy(() -> vaultService.createEntry(USER_1_ID, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Password encrypted blob is required");
        }

        @Test
        @DisplayName("Should reject creation without IV")
        void shouldRejectMissingIV() {
            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder()
                    .title("No IV")
                    .passwordEncrypted(new byte[]{1})
                    .build();

            assertThatThrownBy(() -> vaultService.createEntry(USER_1_ID, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IV is required");
        }

        @Test
        @DisplayName("Should allow optional fields to be null")
        void shouldAllowOptionalFieldsNull() {
            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder()
                    .title("Minimal Entry")
                    .passwordEncrypted(new byte[]{1, 2})
                    .iv(new byte[]{3, 4})
                    .build();

            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, dto);

            assertThat(created).isNotNull();
            assertThat(created.getTitle()).isEqualTo("Minimal Entry");
            assertThat(created.getUsernameEncrypted()).isNull();
            assertThat(created.getNotesEncrypted()).isNull();
            assertThat(created.getUrl()).isNull();
            assertThat(created.getGroupPath()).isNull();
        }
    }

    @Nested
    @DisplayName("Read Entries Integration Tests")
    class ReadEntriesTests {

        @Test
        @DisplayName("Should return all entries for a user")
        void shouldGetAllEntriesByUser() {
            vaultService.createEntry(USER_1_ID, createTestDTO("Entry 1"));
            vaultService.createEntry(USER_1_ID, createTestDTO("Entry 2"));
            vaultService.createEntry(USER_1_ID, createTestDTO("Entry 3"));

            List<EncryptedVaultEntryDTO> entries = vaultService.getEntriesByUser(USER_1_ID);

            assertThat(entries).hasSize(3);
        }

        @Test
        @DisplayName("Should return empty list when user has no entries")
        void shouldReturnEmptyForUserWithNoEntries() {
            List<EncryptedVaultEntryDTO> entries = vaultService.getEntriesByUser(USER_1_ID);

            assertThat(entries).isEmpty();
        }

        @Test
        @DisplayName("Should return entry by ID when user owns it")
        void shouldGetEntryByIdForOwner() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("My Entry"));

            EncryptedVaultEntryDTO found = vaultService.getEntryById(created.getId(), USER_1_ID);

            assertThat(found).isNotNull();
            assertThat(found.getTitle()).isEqualTo("My Entry");
        }

        @Test
        @DisplayName("Should throw when entry not found")
        void shouldThrowWhenEntryNotFound() {
            assertThatThrownBy(() -> vaultService.getEntryById(999L, USER_1_ID))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Vault entry not found with id: 999");
        }

        @Test
        @DisplayName("Should throw when user tries to access another users entry")
        void shouldThrowWhenAccessingOtherUserEntry() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Secret"));

            assertThatThrownBy(() -> vaultService.getEntryById(created.getId(), USER_2_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to the specified user");
        }
    }

    @Nested
    @DisplayName("Update Entry Integration Tests")
    class UpdateEntryTests {

        @Test
        @DisplayName("Should update entry fields for owner")
        void shouldUpdateEntryForOwner() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Original"));

            EncryptedVaultEntryDTO updateDto = EncryptedVaultEntryDTO.builder()
                    .title("Updated Title")
                    .usernameEncrypted(new byte[]{0x10})
                    .passwordEncrypted(new byte[]{0x20, 0x30})
                    .notesEncrypted(new byte[]{0x40})
                    .url("https://updated.com")
                    .groupPath("/personal")
                    .iv(new byte[]{(byte) 0xEE, (byte) 0xFF})
                    .build();

            EncryptedVaultEntryDTO updated = vaultService.updateEntry(created.getId(), USER_1_ID, updateDto);

            assertThat(updated.getTitle()).isEqualTo("Updated Title");
            assertThat(updated.getUrl()).isEqualTo("https://updated.com");
            assertThat(updated.getGroupPath()).isEqualTo("/personal");
        }

        @Test
        @DisplayName("Should throw when updating non-existent entry")
        void shouldThrowWhenUpdateNonExistent() {
            EncryptedVaultEntryDTO dto = createTestDTO("New");

            assertThatThrownBy(() -> vaultService.updateEntry(999L, USER_1_ID, dto))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Vault entry not found with id: 999");
        }

        @Test
        @DisplayName("Should throw when user tries to update another users entry")
        void shouldThrowWhenUpdatingOtherUserEntry() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Secret"));
            EncryptedVaultEntryDTO dto = createTestDTO("Hacked");

            assertThatThrownBy(() -> vaultService.updateEntry(created.getId(), USER_2_ID, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to the specified user");
        }

        @Test
        @DisplayName("Should reject update with null DTO")
        void shouldRejectNullDTO() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Original"));

            assertThatThrownBy(() -> vaultService.updateEntry(created.getId(), USER_1_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DTO cannot be null");
        }

        @Test
        @DisplayName("Should reject update with blank title")
        void shouldRejectBlankTitle() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Original"));
            EncryptedVaultEntryDTO dto = EncryptedVaultEntryDTO.builder().title("").build();

            assertThatThrownBy(() -> vaultService.updateEntry(created.getId(), USER_1_ID, dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Title is required");
        }
    }

    @Nested
    @DisplayName("Delete Entry Integration Tests")
    class DeleteEntryTests {

        @Test
        @DisplayName("Should delete entry for owner")
        void shouldDeleteEntryForOwner() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("To Delete"));
            Long id = created.getId();

            vaultService.deleteEntry(id, USER_1_ID);

            assertThatThrownBy(() -> vaultService.getEntryById(id, USER_1_ID))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        }

        @Test
        @DisplayName("Should throw when deleting non-existent entry")
        void shouldThrowWhenDeleteNonExistent() {
            assertThatThrownBy(() -> vaultService.deleteEntry(999L, USER_1_ID))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                    .hasMessageContaining("Vault entry not found with id: 999");
        }

        @Test
        @DisplayName("Should throw when user tries to delete another users entry")
        void shouldThrowWhenDeletingOtherUserEntry() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Secret"));

            assertThatThrownBy(() -> vaultService.deleteEntry(created.getId(), USER_2_ID))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to the specified user");
        }

        @Test
        @DisplayName("Should leave other users entries intact after deletion")
        void shouldNotAffectOtherUserEntries() {
            vaultService.createEntry(USER_1_ID, createTestDTO("User 1 Entry"));
            EncryptedVaultEntryDTO user2Entry = vaultService.createEntry(USER_2_ID, createTestDTO("User 2 Entry"));

            List<EncryptedVaultEntryDTO> before = vaultService.getEntriesByUser(USER_1_ID);
            assertThat(before).hasSize(1);

            vaultService.deleteEntry(before.get(0).getId(), USER_1_ID);

            List<EncryptedVaultEntryDTO> user2After = vaultService.getEntriesByUser(USER_2_ID);
            assertThat(user2After).hasSize(1);
            assertThat(user2After.get(0).getTitle()).isEqualTo("User 2 Entry");
        }
    }

    @Nested
    @DisplayName("Full CRUD Cycle Integration Tests")
    class FullCrudCycleTests {

        @Test
        @DisplayName("Should complete full CRUD cycle: Create -> Read -> Update -> Delete")
        void shouldCompleteFullCrudCycle() {
            // CREATE
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("CRUD Test"));
            Long id = created.getId();

            // READ (by user)
            List<EncryptedVaultEntryDTO> entries = vaultService.getEntriesByUser(USER_1_ID);
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getId()).isEqualTo(id);

            // READ (by ID)
            EncryptedVaultEntryDTO byId = vaultService.getEntryById(id, USER_1_ID);
            assertThat(byId.getTitle()).isEqualTo("CRUD Test");

            // UPDATE
            EncryptedVaultEntryDTO updateDto = EncryptedVaultEntryDTO.builder()
                    .title("Updated CRUD")
                    .passwordEncrypted(new byte[]{(byte) 0xFF})
                    .iv(new byte[]{0x00})
                    .build();
            EncryptedVaultEntryDTO updated = vaultService.updateEntry(id, USER_1_ID, updateDto);
            assertThat(updated.getTitle()).isEqualTo("Updated CRUD");

            // READ after update
            EncryptedVaultEntryDTO afterUpdate = vaultService.getEntryById(id, USER_1_ID);
            assertThat(afterUpdate.getTitle()).isEqualTo("Updated CRUD");

            // DELETE
            vaultService.deleteEntry(id, USER_1_ID);

            // VERIFY deleted
            assertThatThrownBy(() -> vaultService.getEntryById(id, USER_1_ID))
                    .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("Ownership Validation Integration Tests")
    class OwnershipValidationTests {

        @Test
        @DisplayName("Should enforce ownership on all operations")
        void shouldEnforceOwnershipOnAllOperations() {
            EncryptedVaultEntryDTO created = vaultService.createEntry(USER_1_ID, createTestDTO("Secret"));
            Long id = created.getId();

            // Read - other user cannot read
            assertThatThrownBy(() -> vaultService.getEntryById(id, USER_2_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            // Update - other user cannot update
            assertThatThrownBy(() -> vaultService.updateEntry(id, USER_2_ID, createTestDTO("Hacked")))
                    .isInstanceOf(IllegalArgumentException.class);

            // Delete - other user cannot delete
            assertThatThrownBy(() -> vaultService.deleteEntry(id, USER_2_ID))
                    .isInstanceOf(IllegalArgumentException.class);

            // Entry still exists for owner
            EncryptedVaultEntryDTO found = vaultService.getEntryById(id, USER_1_ID);
            assertThat(found.getTitle()).isEqualTo("Secret");
        }
    }
}
