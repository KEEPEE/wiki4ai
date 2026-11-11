package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * WIKI4AI-197 (D1): confirmation of a re-encryption batch.
 *
 * <p>{@code ids} is echoed back so a client that never received this response — the
 * connection dropped after the commit — can find out what happened by re-reading the
 * entries rather than by guessing. Re-sending the same batch then conflicts on the
 * rows that already moved, which is the correct answer, not a failure.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VaultV2MigrateResponseDTO {

    private int migrated;

    private List<Long> ids;
}
