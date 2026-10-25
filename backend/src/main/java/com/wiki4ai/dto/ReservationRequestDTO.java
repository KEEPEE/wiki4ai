package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload of {@code POST /api/v1/resources/{key}/reserve} (WIKI4AI-156).
 *
 * <h2>Why {@code slots} is a String</h2>
 * The AC is "slots is not a number → 400 with a message naming slots". This project's
 * {@code GlobalExceptionHandler} — which this story must not modify — has no handler for
 * {@code HttpMessageNotReadableException}, so binding {@code {"slots": "abc"}} to an
 * {@code Integer} field would fail during JSON parsing and be answered with a 500 by the
 * catch-all. Declaring the field as a String keeps the value in the request DTO, lets
 * Jackson accept a JSON number as well ({@code {"slots": 2}} arrives as {@code "2"}), and
 * moves the decision to {@code ResourceService}, which answers with
 * {@code BadRequestException} → 400. The number is parsed exactly once, there.
 *
 * <p>{@code workItemId} is optional. When present, the caller must also have write access
 * to that work item's project — see the access rules in {@code ResourceService}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReservationRequestDTO {

    /** How many units to take. Parsed and validated in the service; null defaults to 1. */
    private String slots;

    /** Free-text justification, at most 255 characters. */
    private String reason;

    /** Optional work item this holding is held for. */
    private Long workItemId;
}
