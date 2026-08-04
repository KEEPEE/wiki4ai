package com.wiki4ai.service;

import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.model.VaultEntry;
import com.wiki4ai.repository.VaultEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service layer for Vault entries.
 * Works only with encrypted blobs - never decrypts data.
 * All mutating operations validate ownership via userId parameter.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VaultService {

    private final VaultEntryRepository vaultEntryRepository;

    /**
     * Create a new vault entry for the given user.
     */
    @Transactional
    public EncryptedVaultEntryDTO createEntry(Long userId, EncryptedVaultEntryDTO dto) {
        validateCreateDTO(dto);

        VaultEntry entry = VaultEntry.builder()
                .userId(userId)
                .title(dto.getTitle())
                .usernameEncrypted(dto.getUsernameEncrypted())
                .passwordEncrypted(dto.getPasswordEncrypted())
                .notesEncrypted(dto.getNotesEncrypted())
                .url(dto.getUrl())
                .groupPath(dto.getGroupPath())
                .iv(dto.getIv())
                .build();

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToDTO(saved);
    }

    /**
     * Get all vault entries for a user.
     */
    public List<EncryptedVaultEntryDTO> getEntriesByUser(Long userId) {
        return vaultEntryRepository.findByUserId(userId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single vault entry by ID, verifying ownership.
     */
    public EncryptedVaultEntryDTO getEntryById(Long id, Long userId) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        return convertToDTO(entry);
    }

    /**
     * Update an existing vault entry, verifying ownership.
     */
    @Transactional
    public EncryptedVaultEntryDTO updateEntry(Long id, Long userId, EncryptedVaultEntryDTO dto) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        validateUpdateDTO(dto);

        entry.setTitle(dto.getTitle());
        entry.setUsernameEncrypted(dto.getUsernameEncrypted());
        entry.setPasswordEncrypted(dto.getPasswordEncrypted());
        entry.setNotesEncrypted(dto.getNotesEncrypted());
        entry.setUrl(dto.getUrl());
        entry.setGroupPath(dto.getGroupPath());
        entry.setIv(dto.getIv());

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToDTO(saved);
    }

    /**
     * Delete a vault entry, verifying ownership.
     */
    @Transactional
    public void deleteEntry(Long id, Long userId) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        vaultEntryRepository.delete(entry);
    }

    // ==================== VALIDATION ====================

    private void validateCreateDTO(EncryptedVaultEntryDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (dto.getPasswordEncrypted() == null) {
            throw new IllegalArgumentException("Password encrypted blob is required");
        }
        if (dto.getIv() == null) {
            throw new IllegalArgumentException("IV is required");
        }
    }

    private void validateUpdateDTO(EncryptedVaultEntryDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
    }

    // ==================== CONVERSION ====================

    private EncryptedVaultEntryDTO convertToDTO(VaultEntry entry) {
        return EncryptedVaultEntryDTO.builder()
                .id(entry.getId())
                .title(entry.getTitle())
                .usernameEncrypted(entry.getUsernameEncrypted())
                .passwordEncrypted(entry.getPasswordEncrypted())
                .notesEncrypted(entry.getNotesEncrypted())
                .url(entry.getUrl())
                .groupPath(entry.getGroupPath())
                .iv(entry.getIv())
                .build();
    }
}
