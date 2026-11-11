package com.wiki4ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WIKI4AI-197 (D1): a whole re-encryption batch, sent as one request because it is
 * one transaction.
 *
 * <p>Atomicity is not a nicety here. A batch that half-applied would leave some rows
 * readable only under v2 and others only under v1, with the client holding no record
 * of which is which — and the v1 copy of a row that has moved to v2 is the only thing
 * that makes a rollback survivable, so a partially moved vault is a vault whose
 * rollback story has quietly changed. Either every row in the batch moves, or none
 * does.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultV2MigrateRequestDTO {

    /**
     * The key the v2 fields were encrypted under. It has to be the key this user's
     * vault is initialised with; a batch encrypted under some other key would store
     * bytes nobody here can open.
     */
    @NotBlank(message = "Key id is required")
    @Size(max = 64, message = "Key id must not exceed 64 characters")
    private String keyId;

    @Valid
    @NotEmpty(message = "At least one entry is required")
    @Size(max = 500, message = "A migrate batch must not exceed 500 entries")
    private List<VaultV2MigrateEntryDTO> entries;
}
