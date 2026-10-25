package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Response view of a {@code Resource} (WIKI4AI-155).
 * <p>
 * {@code activeSlots} is reported — reading the counter is unrestricted — but it is not
 * writable: see {@code ResourceRequestDTO} for why the request side has no such field.
 * {@code availableSlots} is {@code capacity - activeSlots}, computed on conversion so a
 * client does not have to re-derive the invariant and cannot derive it wrongly.
 * <p>
 * {@code createdAt} is a {@link LocalDateTime}, matching the TIMESTAMP column of V25
 * (SPEC rule 11b). No {@code Instant} anywhere in this package.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceDTO {

    private Long id;

    /** The identifier the reservation endpoints address the resource by. */
    private String key;

    private String name;

    private String kind;

    private Integer capacity;

    /** Units held right now. Read-only by construction. */
    private Integer activeSlots;

    private Boolean enabled;

    private String metadata;

    /** {@code capacity - activeSlots}: what a caller can still reserve. */
    private Integer availableSlots;

    private LocalDateTime createdAt;
}
