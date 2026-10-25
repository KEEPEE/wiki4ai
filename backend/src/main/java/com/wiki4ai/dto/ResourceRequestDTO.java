package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload of {@code /api/v1/resources} — POST (create) and PUT (update)
 * (WIKI4AI-155).
 * <p>
 * One class for both verbs on purpose, like {@code WorkItemRequestDTO}: PUT has PATCH
 * semantics, a null field means "do not change it". Required-ness is decided in
 * {@code ResourceService}, not by bean validation, because what is required depends on
 * the verb.
 *
 * <h2>What is deliberately NOT here: {@code activeSlots}</h2>
 * The held-slot counter is not client-writable. It is the sum of the live reservations of
 * the resource, and the only statements that move it are the guarded UPDATEs of
 * {@code ResourceRepository}. A field here would mean a {@code PUT} could set the counter
 * to whatever a caller liked and silently disconnect it from the reservation rows — the
 * exact drift WIKI4AI-156 exists to prevent. {@code ReservationAtomicityIntegrationTest}
 * asserts by reflection that this class has no such field and that {@code Resource} has no
 * setter for it, so the shape cannot rot back.
 * <p>
 * {@code capacity} IS here, but {@code ResourceService.update} refuses a capacity below the
 * slots currently held (409) — otherwise lowering the ceiling under a live reservation
 * would break the invariant through an ordinary update.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceRequestDTO {

    /** Globally unique identifier. Required on POST; on PUT, null means "keep the key". */
    private String key;

    /** Display name. Required on POST. */
    private String name;

    /** Category. Null defaults to {@code generic}. */
    private String kind;

    /** How many units exist. Null defaults to 1 on POST. Must stay >= the held slots. */
    private Integer capacity;

    /** Null means "unchanged" on PUT, {@code true} on POST. */
    private Boolean enabled;

    /** Free-form metadata. */
    private String metadata;
}
