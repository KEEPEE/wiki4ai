package com.wiki4ai.service;

import com.wiki4ai.dto.EncryptedField;
import com.wiki4ai.dto.EncryptedVaultEntryDTO;
import com.wiki4ai.dto.VaultEntryRequestDTO;
import com.wiki4ai.dto.VaultEntryResponseDTO;
import com.wiki4ai.model.VaultEntry;
import com.wiki4ai.repository.VaultEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
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
    public VaultEntryResponseDTO createEntry(Long userId, VaultEntryRequestDTO dto) {
        validateCreateDTO(dto);

        byte[] usernameEncrypted = decodeEncryptedField(dto.getUsernameEncrypted());
        byte[] passwordEncrypted = decodeEncryptedField(dto.getPasswordEncrypted());
        byte[] notesEncrypted = decodeEncryptedField(dto.getNotesEncrypted());

        VaultEntry entry = VaultEntry.builder()
                .userId(userId)
                .title(dto.getTitle())
                .usernameEncrypted(usernameEncrypted)
                .passwordEncrypted(passwordEncrypted)
                .notesEncrypted(notesEncrypted)
                .url(dto.getUrl())
                .groupPath(dto.getGroupPath())
                .iv(extractIv(dto.getPasswordEncrypted()))
                .build();

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToResponseDTO(saved);
    }

    /**
     * Get all vault entries for a user.
     */
    public List<VaultEntryResponseDTO> getEntriesByUser(Long userId) {
        return vaultEntryRepository.findByUserId(userId)
                .stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a single vault entry by ID, verifying ownership.
     */
    public VaultEntryResponseDTO getEntryById(Long id, Long userId) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        return convertToResponseDTO(entry);
    }

    /**
     * Update an existing vault entry, verifying ownership.
     */
    @Transactional
    public VaultEntryResponseDTO updateEntry(Long id, Long userId, VaultEntryRequestDTO dto) {
        VaultEntry entry = vaultEntryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Vault entry not found with id: " + id));

        if (!entry.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Vault entry does not belong to the specified user");
        }

        validateUpdateDTO(dto);

        byte[] usernameEncrypted = decodeEncryptedField(dto.getUsernameEncrypted());
        byte[] passwordEncrypted = decodeEncryptedField(dto.getPasswordEncrypted());
        byte[] notesEncrypted = decodeEncryptedField(dto.getNotesEncrypted());

        entry.setTitle(dto.getTitle());
        entry.setUsernameEncrypted(usernameEncrypted);
        entry.setPasswordEncrypted(passwordEncrypted);
        entry.setNotesEncrypted(notesEncrypted);
        entry.setUrl(dto.getUrl());
        entry.setGroupPath(dto.getGroupPath());
        entry.setIv(extractIv(dto.getPasswordEncrypted()));

        VaultEntry saved = vaultEntryRepository.save(entry);
        return convertToResponseDTO(saved);
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

    /**
     * Search vault entries by title and URL for a specific user.
     * Optionally filters by group_path prefix.
     */
    public List<VaultEntryResponseDTO> searchEntries(Long userId, String query, String groupPathPrefix) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        List<VaultEntry> entries;
        if (groupPathPrefix != null && !groupPathPrefix.isBlank()) {
            entries = vaultEntryRepository.findByUserIdAndGroupPathStartingWithAndTitleContainingIgnoreCaseOrUrlContainingIgnoreCase(
                    userId, groupPathPrefix, query, query);
        } else {
            entries = vaultEntryRepository.findByUserIdAndTitleContainingIgnoreCaseOrUrlContainingIgnoreCase(
                    userId, query, query);
        }

        return entries.stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    // ==================== VALIDATION ====================

    private void validateCreateDTO(VaultEntryRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
        if (dto.getPasswordEncrypted() == null) {
            throw new IllegalArgumentException("Password encrypted field is required");
        }
        if (dto.getPasswordEncrypted().getIv() == null || dto.getPasswordEncrypted().getIv().isBlank()) {
            throw new IllegalArgumentException("IV is required in password encrypted field");
        }
    }

    private void validateUpdateDTO(VaultEntryRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("DTO cannot be null");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new IllegalArgumentException("Title is required");
        }
    }

    // ==================== CONVERSION ====================

    private VaultEntryResponseDTO convertToResponseDTO(VaultEntry entry) {
        return VaultEntryResponseDTO.builder()
                .id(entry.getId())
                .title(entry.getTitle())
                .usernameEncrypted(toEncryptedField(entry.getUsernameEncrypted(), entry.getIv()))
                .passwordEncrypted(toEncryptedField(entry.getPasswordEncrypted(), entry.getIv()))
                .notesEncrypted(toEncryptedField(entry.getNotesEncrypted(), null))
                .url(entry.getUrl())
                .groupPath(entry.getGroupPath())
                .createdAt(entry.getCreatedAt())
                .updatedAt(entry.getUpdatedAt())
                .build();
    }

    private EncryptedField toEncryptedField(byte[] data, byte[] iv) {
        if (data == null || data.length == 0) {
            return null;
        }
        var builder = EncryptedField.builder()
                .ciphertext(Base64.getEncoder().encodeToString(data));
        if (iv != null && iv.length > 0) {
            builder.iv(Base64.getEncoder().encodeToString(iv));
        }
        return builder.build();
    }

    private byte[] decodeEncryptedField(EncryptedField field) {
        if (field == null || field.getCiphertext() == null || field.getCiphertext().isBlank()) {
            return null;
        }
        return Base64.getDecoder().decode(field.getCiphertext());
    }

    private byte[] extractIv(EncryptedField field) {
        if (field == null || field.getIv() == null || field.getIv().isBlank()) {
            return null;
        }
        return Base64.getDecoder().decode(field.getIv());
    }
}
