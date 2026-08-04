package com.wiki4ai.repository;

import com.wiki4ai.model.VaultEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class VaultEntryRepositoryTest {

    @Autowired
    private VaultEntryRepository vaultEntryRepository;

    private VaultEntry createVaultEntry(Long userId, String title, byte[] passwordEncrypted, byte[] iv) {
        return VaultEntry.builder()
                .userId(userId)
                .title(title)
                .passwordEncrypted(passwordEncrypted)
                .iv(iv)
                .build();
    }

    @Test
    @DisplayName("Should save and retrieve vault entry with timestamps")
    void shouldSaveAndRetrieveWithTimestamps() {
        VaultEntry entry = createVaultEntry(1L, "My Entry", new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        VaultEntry saved = vaultEntryRepository.save(entry);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should find all entries by user ID")
    void shouldFindByUserId() {
        VaultEntry entry1 = createVaultEntry(1L, "Entry 1", new byte[]{1}, new byte[]{2});
        VaultEntry entry2 = createVaultEntry(1L, "Entry 2", new byte[]{3}, new byte[]{4});
        VaultEntry entryOtherUser = createVaultEntry(2L, "Other User Entry", new byte[]{5}, new byte[]{6});

        vaultEntryRepository.save(entry1);
        vaultEntryRepository.save(entry2);
        vaultEntryRepository.save(entryOtherUser);

        List<VaultEntry> entries = vaultEntryRepository.findByUserId(1L);

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(VaultEntry::getTitle))
                .containsExactlyInAnyOrder("Entry 1", "Entry 2");
    }

    @Test
    @DisplayName("Should return empty list when no entries for user")
    void shouldReturnEmptyWhenNoEntriesForUser() {
        List<VaultEntry> entries = vaultEntryRepository.findByUserId(99L);

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Should find entries by user ID and group path containing")
    void shouldFindByUserIdAndGroupPathContaining() {
        VaultEntry entry1 = createVaultEntry(1L, "Work Entry", new byte[]{1}, new byte[]{2});
        entry1.setGroupPath("/work/projects");
        VaultEntry entry2 = createVaultEntry(1L, "Personal Entry", new byte[]{3}, new byte[]{4});
        entry2.setGroupPath("/personal/finance");
        VaultEntry entry3 = createVaultEntry(1L, "Another Work Entry", new byte[]{5}, new byte[]{6});
        entry3.setGroupPath("/work/tools");

        vaultEntryRepository.save(entry1);
        vaultEntryRepository.save(entry2);
        vaultEntryRepository.save(entry3);

        List<VaultEntry> entries = vaultEntryRepository.findByUserIdAndGroupPathContaining(1L, "/work");

        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(VaultEntry::getTitle))
                .containsExactlyInAnyOrder("Work Entry", "Another Work Entry");
    }

    @Test
    @DisplayName("Should return empty list when no entries match group path")
    void shouldReturnEmptyWhenNoEntriesMatchGroupPath() {
        VaultEntry entry = createVaultEntry(1L, "Entry", new byte[]{1}, new byte[]{2});
        entry.setGroupPath("/other/path");
        vaultEntryRepository.save(entry);

        List<VaultEntry> entries = vaultEntryRepository.findByUserIdAndGroupPathContaining(1L, "/nonexistent");

        assertThat(entries).isEmpty();
    }

    @Test
    @DisplayName("Should store byte arrays correctly")
    void shouldStoreByteArraysCorrectly() {
        byte[] passwordEncrypted = new byte[]{0x01, 0x02, 0x03, (byte) 0xFF};
        byte[] iv = new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD};

        VaultEntry entry = createVaultEntry(1L, "Encrypted Entry", passwordEncrypted, iv);
        vaultEntryRepository.save(entry);

        VaultEntry found = vaultEntryRepository.findById(entry.getId()).orElseThrow();

        assertThat(found.getPasswordEncrypted()).isEqualTo(passwordEncrypted);
        assertThat(found.getIv()).isEqualTo(iv);
    }

    @Test
    @DisplayName("Should delete entry by id")
    void shouldDeleteEntryById() {
        VaultEntry saved = vaultEntryRepository.save(createVaultEntry(1L, "To Delete", new byte[]{1}, new byte[]{2}));
        Long id = saved.getId();

        vaultEntryRepository.deleteById(id);

        assertThat(vaultEntryRepository.findById(id)).isEmpty();
    }
}
