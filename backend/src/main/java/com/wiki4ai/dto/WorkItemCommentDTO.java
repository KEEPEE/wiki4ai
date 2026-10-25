package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response payload of the comment endpoints (WIKI4AI-148):
 * {@code POST /api/v1/work-items/{id}/comments} and
 * {@code GET /api/v1/work-items/{id}/comments}.
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 *
 * <h2>There is no {@code updatedAt}</h2>
 * Not omitted for tidiness: the column does not exist (V22). A comment is immutable,
 * so there is no later moment to report, and a DTO field with no column behind it
 * would be a promise the schema cannot keep.
 *
 * <h2>{@code authorActorId} is a fact, not an input</h2>
 * It is the actor the credential named when the comment was written. It cannot be
 * chosen by the caller, and it is not re-resolved at read time.
 *
 * <h2>{@code payload}</h2>
 * The stored JSON TEXT parsed back into a {@code Map<String,Object>} — the mirror of
 * the request DTO, and the same arrangement {@code work_items.labels} uses between its
 * TEXT column and its {@code List<String>} DTO. Null for a comment that carries no
 * payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Jeden komentár work itemu (nemenný)")
public class WorkItemCommentDTO {

    private Long id;

    @Schema(description = "Work item, ktorého sa komentár týka")
    private Long workItemId;

    @Schema(description = "Actor, ktorý komentár napísal — z overeného credentialu, nikdy z tela requestu")
    private Long authorActorId;

    @Schema(description = "status | blocker | result | handoff | note | decision")
    private String kind;

    @Schema(description = "Text komentára")
    private String body;

    @Schema(description = "Štruktúrované detaily kindu; null pre komentár bez payloadu")
    private Map<String, Object> payload;

    @Schema(description = "Kedy bol komentár napísan. Nie je tu updated_at: komentár je nemenný.")
    private LocalDateTime createdAt;
}
