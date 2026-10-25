package com.wiki4ai.service;

import com.wiki4ai.exception.CommentContractException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <h2>THE contract of a work item comment (WIKI4AI-149)</h2>
 * Every rule a comment's {@code payload} has to satisfy, for every {@code kind}, in
 * one table and one method. This class is deliberately the only place in the
 * application that knows them: a contract spread across a service, a controller and a
 * DTO is a contract whose copies drift, and the AC of WIKI4AI-149 is a list of
 * per-kind field rules, which is exactly the shape of a table.
 *
 * <pre>
 *   kind      required payload fields
 *   ────────  ─────────────────────────────────────────────────────────────
 *   status    (none)
 *   note      (none)
 *   result    where: text   next: text   artifacts: ARRAY
 *   blocker   blocking: text   needs_human: BOOLEAN
 *   handoff   to_handle: HANDLE   context: text
 *   decision  what: text   why: text   applies_to: text
 * </pre>
 *
 * <h2>Enforced by the server, in code, not in a prompt</h2>
 * The point of WIKI4AI-149 is that these shapes are a guarantee a reader of a comment
 * can rely on. A prompt asking agents to send the right fields produces comments that
 * usually have them; this produces comments that have them, because a comment that
 * does not is never written. There is no JSON Schema library in this project and none
 * is added — the rules are ten lines of Java against the {@code Map} Jackson already
 * built. Nor is any of it in the database: V22 stores the payload as TEXT and adds no
 * CHECK on {@code kind}, exactly as V21 did for {@code work_item_events.event}
 * (SPEC rule 7 — business rules live in the service layer).
 *
 * <h2>What a violation costs</h2>
 * Every rule here is a violation of a contract the request could have satisfied, so
 * every one of them is a {@link CommentContractException}, which
 * {@code GlobalExceptionHandler} maps to <b>422</b>. A missing {@code body} or an
 * unknown {@code kind} is not a contract violation — it is a malformed request — and
 * {@code WorkItemCommentService} answers those with 400 before this class is reached.
 *
 * <h2>Unknown keys are allowed</h2>
 * A payload may carry keys this table does not list, and they are kept. The contract
 * is a <b>minimum</b>, not a closed schema: {@code result} has to say where, next and
 * what it produced, and nothing says it may not also say how long it took or which
 * commit it landed in. Rejecting an unknown key would turn a minimum into a closed
 * schema the SPEC never declared, and it would break forward compatibility — a newer
 * agent adding a useful field would be refused by an older server. Strictness here is
 * only worth having about the fields a reader is guaranteed to find.
 *
 * <h2>What is not checked</h2>
 * Element types inside an array, and the content of a text field. The contract names
 * the fields a reader may rely on and the shape each one has to have; the values
 * inside them are the author's.
 */
public final class WorkItemCommentContract {

    // The six kinds. Mirrors the KIND_* constants of WorkItemComment; kept here as
    // keys of the contract table so the table is the thing that decides which kinds
    // exist at all.
    public static final String KIND_STATUS = "status";
    public static final String KIND_NOTE = "note";
    public static final String KIND_RESULT = "result";
    public static final String KIND_BLOCKER = "blocker";
    public static final String KIND_HANDOFF = "handoff";
    public static final String KIND_DECISION = "decision";

    /**
     * The payload field whose value has to name a real actor. Its shape is checked
     * here; the extra rules — that the handle must belong to an existing actor whose
     * status is {@code active}, <b>and</b> that that actor must be a member of the
     * project the item lives in (WIKI4AI-174) — need the actor registry and the project
     * membership table, so they are the rules of this contract the service enforces
     * ({@code WorkItemCommentService.requireHandoffTarget}), and they are named here so
     * that the whole contract is still readable from this file. A handoff to an
     * actor who does not exist is the kind of mistake that is otherwise invisible
     * forever: the comment is written, the item sits, nobody comes. A handoff to an
     * actor who exists, is active, and has no role in this project is the same mistake
     * one step later — the comment is written, the item sits, and the actor's inbox
     * never carries the project it is scoped to.
     */
    public static final String HANDOFF_TARGET_FIELD = "to_handle";

    /** The shape a required field has to have. */
    public enum FieldType {

        /** A non-blank string. */
        TEXT,

        /** A JSON array — a scalar where an array is required is a contract break. */
        ARRAY,

        /**
         * A JSON boolean. A string {@code "true"} is a contract break: this field is
         * a filterable flag, the thing a later query filters blockers on, and a value
         * that is sometimes {@code true} and sometimes {@code "true"} cannot be
         * filtered at all.
         */
        BOOLEAN,

        /** A non-blank string that must also name a live actor (see {@link #HANDOFF_TARGET_FIELD}). */
        HANDLE
    }

    /** One required field of one kind. */
    public record Field(String name, FieldType type) {
    }

    /**
     * The contract table. One entry per kind; an empty list means "this kind requires
     * no payload at all", which is a stated rule and not an omission — {@code status}
     * and {@code note} are comments whose whole content is their body.
     */
    private static final Map<String, List<Field>> CONTRACTS = buildContracts();

    private static Map<String, List<Field>> buildContracts() {
        Map<String, List<Field>> contracts = new LinkedHashMap<>();
        contracts.put(KIND_STATUS, List.of());
        contracts.put(KIND_NOTE, List.of());
        contracts.put(KIND_RESULT, List.of(
                new Field("where", FieldType.TEXT),
                new Field("next", FieldType.TEXT),
                new Field("artifacts", FieldType.ARRAY)));
        contracts.put(KIND_BLOCKER, List.of(
                new Field("blocking", FieldType.TEXT),
                new Field("needs_human", FieldType.BOOLEAN)));
        contracts.put(KIND_HANDOFF, List.of(
                new Field(HANDOFF_TARGET_FIELD, FieldType.HANDLE),
                new Field("context", FieldType.TEXT)));
        contracts.put(KIND_DECISION, List.of(
                new Field("what", FieldType.TEXT),
                new Field("why", FieldType.TEXT),
                new Field("applies_to", FieldType.TEXT)));
        return Collections.unmodifiableMap(contracts);
    }

    private WorkItemCommentContract() {
    }

    /** The kinds this contract knows. A kind outside this set is not a comment kind. */
    public static Set<String> kinds() {
        return CONTRACTS.keySet();
    }

    /** Whether {@code kind} is one of the six. */
    public static boolean isKnownKind(String kind) {
        return kind != null && CONTRACTS.containsKey(kind);
    }

    /** The required fields of a kind; empty for the kinds that require none. */
    public static List<Field> requiredFields(String kind) {
        return CONTRACTS.getOrDefault(kind, List.of());
    }

    /**
     * Check a payload against the contract of its kind.
     * <p>
     * Every violation is collected before throwing, so one response names every field
     * the caller has to fix instead of making it fix one, retry, and hit the next.
     *
     * @param kind    the kind of the comment, already normalised and already known
     * @param payload the payload as Jackson deserialised it; {@code null} when the
     *                request carried none — which for a kind with required fields is
     *                itself the violation, not a special case
     * @throws CommentContractException when the payload breaks the contract (422)
     */
    public static void validate(String kind, Map<String, Object> payload) {
        List<Field> required = requiredFields(kind);
        if (required.isEmpty()) {
            // status and note: no contract to break. A payload they do carry is
            // stored as it is.
            return;
        }

        List<String> violations = new ArrayList<>();
        for (Field field : required) {
            Object value = payload == null ? null : payload.get(field.name());
            String violation = check(field, value);
            if (violation != null) {
                violations.add(violation);
            }
        }

        if (!violations.isEmpty()) {
            throw new CommentContractException(kind, violations);
        }
    }

    /**
     * @return a description of the violation, or {@code null} when the field satisfies
     *         its rule
     */
    private static String check(Field field, Object value) {
        // Absent key and explicit null are the same failure: the field is not there.
        if (value == null) {
            return "'" + field.name() + "' is required";
        }
        return switch (field.type()) {
            case TEXT, HANDLE -> (value instanceof String text && !text.isBlank())
                    ? null
                    : "'" + field.name() + "' must be a non-empty text";
            case ARRAY -> value instanceof List<?>
                    ? null
                    : "'" + field.name() + "' must be an array";
            case BOOLEAN -> value instanceof Boolean
                    ? null
                    : "'" + field.name() + "' must be true or false";
        };
    }
}
