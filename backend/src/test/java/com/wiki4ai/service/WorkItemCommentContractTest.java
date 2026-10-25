package com.wiki4ai.service;

import com.wiki4ai.exception.CommentContractException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests of {@link WorkItemCommentContract} — the one place the per-kind payload
 * contracts of WIKI4AI-149 live.
 * <p>
 * No Spring here on purpose. The contract is a table and a loop over it; testing it
 * without a database, a controller or a credential is what makes it possible to assert
 * every kind against every rule, including the rules the HTTP tests reach only one at a
 * time. The HTTP behaviour on top of it — 422 versus 400 — is asserted in
 * {@code WorkItemCommentIntegrationTest}.
 */
@DisplayName("WorkItemCommentContract: the per-kind payload contracts (WIKI4AI-149)")
class WorkItemCommentContractTest {

    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static void assertViolates(String kind, Map<String, Object> payload, String... expectedFragments) {
        assertThatThrownBy(() -> WorkItemCommentContract.validate(kind, payload))
                .isInstanceOf(CommentContractException.class)
                .satisfies(error -> {
                    CommentContractException contractError = (CommentContractException) error;
                    assertThat(contractError.getKind()).isEqualTo(kind);
                    assertThat(contractError.getViolations())
                            .containsAll(List.of(expectedFragments));
                });
    }

    @Nested
    @DisplayName("the vocabulary")
    class Vocabulary {

        @Test
        @DisplayName("exactly the six kinds of SPEC exist")
        void exactlySixKinds() {
            assertThat(WorkItemCommentContract.kinds())
                    .containsExactlyInAnyOrder("status", "note", "result", "blocker", "handoff", "decision");
        }

        @Test
        @DisplayName("an unknown kind is not a kind")
        void unknownKindIsUnknown() {
            assertThat(WorkItemCommentContract.isKnownKind("progress")).isFalse();
            assertThat(WorkItemCommentContract.isKnownKind("RESULT")).isFalse();
            assertThat(WorkItemCommentContract.isKnownKind(null)).isFalse();
        }

        @Test
        @DisplayName("status and note require no field at all")
        void statusAndNoteHaveNoRequiredFields() {
            assertThat(WorkItemCommentContract.requiredFields("status")).isEmpty();
            assertThat(WorkItemCommentContract.requiredFields("note")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the required fields per kind")
    class RequiredFields {

        @Test
        @DisplayName("result: where, next, artifacts (artifacts is an array)")
        void resultContract() {
            assertThat(WorkItemCommentContract.requiredFields("result"))
                    .extracting(WorkItemCommentContract.Field::name)
                    .containsExactly("where", "next", "artifacts");
            assertThat(WorkItemCommentContract.requiredFields("result"))
                    .filteredOn(field -> field.name().equals("artifacts"))
                    .extracting(WorkItemCommentContract.Field::type)
                    .containsExactly(WorkItemCommentContract.FieldType.ARRAY);
        }

        @Test
        @DisplayName("blocker: blocking, needs_human (needs_human is a boolean)")
        void blockerContract() {
            assertThat(WorkItemCommentContract.requiredFields("blocker"))
                    .extracting(WorkItemCommentContract.Field::name)
                    .containsExactly("blocking", "needs_human");
            assertThat(WorkItemCommentContract.requiredFields("blocker"))
                    .filteredOn(field -> field.name().equals("needs_human"))
                    .extracting(WorkItemCommentContract.Field::type)
                    .containsExactly(WorkItemCommentContract.FieldType.BOOLEAN);
        }

        @Test
        @DisplayName("handoff: to_handle (a handle), context")
        void handoffContract() {
            assertThat(WorkItemCommentContract.requiredFields("handoff"))
                    .extracting(WorkItemCommentContract.Field::name)
                    .containsExactly("to_handle", "context");
            assertThat(WorkItemCommentContract.HANDOFF_TARGET_FIELD).isEqualTo("to_handle");
        }

        @Test
        @DisplayName("decision: what, why, applies_to")
        void decisionContract() {
            assertThat(WorkItemCommentContract.requiredFields("decision"))
                    .extracting(WorkItemCommentContract.Field::name)
                    .containsExactly("what", "why", "applies_to");
        }
    }

    @Nested
    @DisplayName("violations")
    class Violations {

        @ParameterizedTest(name = "{0} without ''{1}'' → 422")
        @CsvSource({
                "result, where",
                "result, next",
                "result, artifacts",
                "blocker, blocking",
                "blocker, needs_human",
                "handoff, to_handle",
                "handoff, context",
                "decision, what",
                "decision, why",
                "decision, applies_to",
        })
        @DisplayName("every required field of every kind is required")
        void everyRequiredFieldIsRequired(String kind, String field) {
            Map<String, Object> payload = completePayloadOf(kind);
            payload.remove(field);

            assertViolates(kind, payload, "'" + field + "' is required");
        }

        @ParameterizedTest(name = "{0} with no payload at all → 422")
        @ValueSource(strings = {"result", "blocker", "handoff", "decision"})
        @DisplayName("a kind with required fields and no payload is a violation, not a default")
        void missingPayloadIsAViolation(String kind) {
            assertThatThrownBy(() -> WorkItemCommentContract.validate(kind, null))
                    .isInstanceOf(CommentContractException.class)
                    .satisfies(error -> assertThat(((CommentContractException) error).getViolations())
                            .hasSize(WorkItemCommentContract.requiredFields(kind).size()));
        }

        @ParameterizedTest(name = "{0} with an explicit null for ''{1}'' → 422")
        @CsvSource({
                "result, where",
                "blocker, needs_human",
                "handoff, to_handle",
                "decision, applies_to",
        })
        @DisplayName("an explicit null is the same failure as an absent key")
        void explicitNullIsAbsent(String kind, String field) {
            Map<String, Object> payload = completePayloadOf(kind);
            payload.put(field, null);

            assertViolates(kind, payload, "'" + field + "' is required");
        }

        @ParameterizedTest(name = "artifacts = \"{0}\" → 422")
        @ValueSource(strings = {"commit abc", "true", " "} )
        @DisplayName("artifacts as a string is a contract break")
        void artifactsAsStringIsAViolation(String value) {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("artifacts", value);

            assertViolates("result", payload, "'artifacts' must be an array");
        }

        @ParameterizedTest(name = "artifacts = {0} → 422")
        @ValueSource(ints = {7, 0})
        @DisplayName("artifacts as a number is a contract break")
        void artifactsAsNumberIsAViolation(int value) {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("artifacts", value);

            assertViolates("result", payload, "'artifacts' must be an array");
        }

        @Test
        @DisplayName("artifacts as a boolean → 422")
        void artifactsAsBooleanIsAViolation() {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("artifacts", true);

            assertViolates("result", payload, "'artifacts' must be an array");
        }

        @ParameterizedTest(name = "needs_human = \"{0}\" → 422")
        @ValueSource(strings = {"true", "false", "yes"})
        @DisplayName("needs_human as a string is a contract break: \"true\" is not a flag")
        void needsHumanAsStringIsAViolation(String value) {
            Map<String, Object> payload = completePayloadOf("blocker");
            payload.put("needs_human", value);

            assertViolates("blocker", payload, "'needs_human' must be true or false");
        }

        @ParameterizedTest(name = "needs_human = {0} → 422")
        @ValueSource(ints = {1, 0})
        @DisplayName("needs_human as a number is a contract break")
        void needsHumanAsNumberIsAViolation(int value) {
            Map<String, Object> payload = completePayloadOf("blocker");
            payload.put("needs_human", value);

            assertViolates("blocker", payload, "'needs_human' must be true or false");
        }

        @ParameterizedTest(name = "{0} with ''{1}'' = \"   \" → 422")
        @CsvSource({
                "result, where",
                "blocker, blocking",
                "handoff, context",
                "decision, what",
        })
        @DisplayName("a blank text is not an answer")
        void blankTextIsAViolation(String kind, String field) {
            Map<String, Object> payload = completePayloadOf(kind);
            payload.put(field, "   ");

            assertViolates(kind, payload, "'" + field + "' must be a non-empty text");
        }

        @Test
        @DisplayName("every violation of one kind is reported together")
        void allViolationsTogether() {
            assertThatThrownBy(() -> WorkItemCommentContract.validate("decision", payload()))
                    .isInstanceOf(CommentContractException.class)
                    .satisfies(error -> assertThat(((CommentContractException) error).getViolations())
                            .containsExactly("'what' is required", "'why' is required",
                                    "'applies_to' is required"));
        }
    }

    @Nested
    @DisplayName("what the contract does not require")
    class WhatIsAllowed {

        @ParameterizedTest(name = "{0} with a complete payload → accepted")
        @ValueSource(strings = {"result", "blocker", "handoff", "decision"})
        @DisplayName("a payload that fills the required fields is accepted")
        void completePayloadIsAccepted(String kind) {
            assertThatCode(() -> WorkItemCommentContract.validate(kind, completePayloadOf(kind)))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "{0} with no payload → accepted")
        @ValueSource(strings = {"status", "note"})
        @DisplayName("status and note need nothing")
        void statusAndNoteNeedNoPayload(String kind) {
            assertThatCode(() -> WorkItemCommentContract.validate(kind, null)).doesNotThrowAnyException();
            assertThatCode(() -> WorkItemCommentContract.validate(kind, payload())).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("unknown keys are allowed: the contract is a minimum, not a closed schema")
        void unknownKeysAreAllowed() {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("durationMinutes", 42);
            payload.put("confidence", "high");
            payload.put("notes", Map.of("nested", "value"));

            assertThatCode(() -> WorkItemCommentContract.validate("result", payload))
                    .as("nothing in SPEC closes these payloads, and refusing an unknown key would"
                            + " break a newer agent sending a useful field to an older server")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an empty array satisfies an array requirement")
        void emptyArrayIsAnArray() {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("artifacts", List.of());

            assertThatCode(() -> WorkItemCommentContract.validate("result", payload)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("needs_human false is a valid answer")
        void needsHumanFalseIsValid() {
            Map<String, Object> payload = completePayloadOf("blocker");
            payload.put("needs_human", false);

            assertThatCode(() -> WorkItemCommentContract.validate("blocker", payload)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("element types inside an array are the author's, not the contract's")
        void arrayElementsAreNotConstrained() {
            Map<String, Object> payload = completePayloadOf("result");
            payload.put("artifacts", List.of(1, "path", Map.of("k", "v")));

            assertThatCode(() -> WorkItemCommentContract.validate("result", payload)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("status and note may carry a payload anyway")
        void kindsWithoutRequirementsMayStillCarryPayload() {
            assertThatCode(() -> WorkItemCommentContract.validate("note", payload("mood", "cautious")))
                    .doesNotThrowAnyException();
        }
    }

    /** A payload that satisfies the whole contract of {@code kind}. */
    private static Map<String, Object> completePayloadOf(String kind) {
        return switch (kind) {
            case "result" -> payload("where", "main@dcd51ad", "next", "review",
                    "artifacts", List.of("backend/src/main/java/com/wiki4ai/model/WorkItemComment.java"));
            case "blocker" -> payload("blocking", "no access to the registry", "needs_human", true);
            case "handoff" -> payload("to_handle", "wic-stranger-agent", "context", "needs the frontend part");
            case "decision" -> payload("what", "H2 for tests", "why", "speed", "applies_to", "backend tests");
            default -> payload();
        };
    }
}