package com.wiki4ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request body of {@code POST /api/v1/work-items/{id}/comments} (WIKI4AI-148).
 * <p>
 * Manual conversion — this project does not use MapStruct (SPEC rule 13).
 *
 * <h2>There is no author field, and that is the security rule</h2>
 * {@code author_actor_id} is written exclusively from the verified credential
 * ({@code RequestActorResolver}). A caller that sends {@code authorActorId},
 * {@code author} or anything else naming an actor is <b>ignored</b>: the field does
 * not exist on this class, so Jackson has nowhere to put it, and the stored author is
 * the one the token names. "Who wrote this comment" is precisely the value a caller
 * would most like to fake, so it is the value the API refuses to accept as input.
 * <p>
 * {@code ignoreUnknown = true} states that behaviour instead of inheriting it from
 * Spring Boot's default ({@code FAIL_ON_UNKNOWN_PROPERTIES} is off): an extra key in
 * the body is dropped, not a 400. The same tolerance also keeps a payload key this
 * server does not know about from breaking a request (see
 * {@code WorkItemCommentContract}: the contract is a minimum, not a closed schema).
 *
 * <h2>What each field costs when it is wrong</h2>
 * <ul>
 *   <li>{@code body} missing or blank → <b>400</b>: a required field of the request
 *   is absent ({@code body TEXT NOT NULL} in V22);</li>
 *   <li>{@code kind} missing or not one of the six → <b>400</b>: the request names
 *   something the API does not have;</li>
 *   <li>{@code payload} not satisfying the contract of the kind → <b>422</b>: the
 *   request was understood, its payload breaks a contract.</li>
 * </ul>
 * {@code payload} is a {@code Map<String,Object>} in the DTO and TEXT in the database,
 * exactly as {@code work_items.labels} is a {@code List<String>} in the DTO and TEXT
 * in the column.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Nový komentár work itemu")
public class WorkItemCommentRequestDTO {

    @Schema(description = "status | blocker | result | handoff | note | decision",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "result")
    private String kind;

    @Schema(description = "Text komentára (povinný)", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Implementácia je na main, testy green.")
    private String body;

    @Schema(description = "Štruktúrované detaily podľa kindu. Kontrakt vynucuje server: "
                    + "result → where, next, artifacts[]; blocker → blocking, needs_human; "
                    + "handoff → to_handle, context; decision → what, why, applies_to.",
            example = "{\"where\":\"main\",\"next\":\"review\",\"artifacts\":[\"WIKI4AI-148\"]}")
    private Map<String, Object> payload;
}
