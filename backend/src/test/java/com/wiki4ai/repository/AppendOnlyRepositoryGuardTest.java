package com.wiki4ai.repository;

import com.wiki4ai.model.WorkItemComment;
import com.wiki4ai.model.WorkItemEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIKI4AI-172: an append-only table is only append-only if <b>every</b> way of writing
 * to it that could remove a row is refused.
 * <p>
 * {@link WorkItemEvent} and {@link WorkItemComment} guard themselves with
 * {@code @PreRemove} / {@code @PreUpdate}. Those callbacks are the layer that catches a
 * managed entity touched through an EntityManager, a merge or a cascade — and they are
 * the layer that <b>cannot</b> catch a bulk statement. {@code deleteAllInBatch()},
 * {@code deleteAllByIdInBatch(Iterable)} and {@code deleteInBatch(Iterable)} send
 * {@code DELETE FROM …} straight to the database without loading a single entity, so no
 * lifecycle callback runs for them, and the V21/V22 schema guards are
 * {@code BEFORE UPDATE} triggers (a {@code BEFORE DELETE} trigger would break the
 * {@code ON DELETE CASCADE} SPEC's own DDL declares). The repository interface is the
 * only layer left standing between one call and an erased audit trail.
 *
 * <h2>Why this test derives its list instead of stating it</h2>
 * The refusal list in this project was written down from memory once already, and memory
 * got it wrong in the direction that matters: {@code deleteInBatch(Iterable)} is declared
 * on {@code JpaRepository} and on neither {@code CrudRepository} nor
 * {@code ListCrudRepository}, so a list copied from the CRUD names leaves it inherited.
 * A hardcoded list of method names has the same defect as the memory it replaces — it is
 * frozen at the moment somebody wrote it. If Spring Data adds a mutating method, or if a
 * developer adds one to a repository, a hardcoded test stays green and the guard silently
 * disappears. That is the exact failure this story is about, so the list here is read off
 * the interface at runtime ({@link #mutatingMethodsOf}) and
 * {@link TheDetectionItselfIsTested} proves the reading actually notices a method that is
 * not refused.
 *
 * <h2>What counts as mutating</h2>
 * A method declared on the repository interface hierarchy is refused unless it is a read
 * ({@code find*}, {@code count*}, {@code exists*}, {@code get*} — Spring Data's read
 * naming) or it is the insert path an append-only table exists to allow
 * ({@code save}, {@code saveAll}, {@code saveAllAndFlush}, {@code flush}). Everything
 * else can remove a row or overwrite the state of a row that already exists, and on these
 * two tables that is not available. {@code save} staying callable is not an oversight:
 * {@code WorkItemEventService.record} and {@code WorkItemCommentService.create} append
 * through it, and {@link TheInsertPathIsStillOpen} asserts it still works — a guard that
 * refused inserts would protect an empty table.
 *
 * <h2>Rule 5b</h2>
 * {@link #cleanState()} runs in {@code @BeforeEach} <b>and</b> {@code @AfterEach} and
 * removes only the rows this class created, identified by a {@code work_item_id} no other
 * test uses. No {@code @DirtiesContext}: every context in this suite points at the same
 * named in-memory H2 with {@code ddl-auto: create-drop}, so closing one drops the schema
 * the others are still using.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Append-only repositories refuse every mutating method (WIKI4AI-172)")
class AppendOnlyRepositoryGuardTest {

    /**
     * A work_item_id this class owns exclusively. Rows are cleaned by that id, so
     * {@link #cleanState()} can never touch another test's data — and no real work item
     * can ever have it.
     */
    private static final long SENTINEL_ITEM_ID = 987_654_321L;

    /** Spring Data's read naming: these methods cannot change stored state. */
    private static final List<String> READ_NAME_PREFIXES = List.of("find", "count", "exists", "get");

    /**
     * The write path an append-only table allows: creating rows. Refusing these would make
     * the table impossible to append to, which is not what append-only means.
     */
    private static final Set<String> INSERT_PATH_NAMES = Set.of("save", "saveAll", "saveAllAndFlush", "flush");

    @Autowired
    private WorkItemEventRepository workItemEventRepository;

    @Autowired
    private WorkItemCommentRepository workItemCommentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        // Children before parents, and only this class's own rows. Native SQL is the only
        // way in: the repositories refuse removal by design, and the test schema has no
        // foreign keys (SPEC rule 11), so there is no cascade to lean on either.
        jdbcTemplate.update("DELETE FROM work_item_comments WHERE work_item_id = ?", SENTINEL_ITEM_ID);
        jdbcTemplate.update("DELETE FROM work_item_events WHERE work_item_id = ?", SENTINEL_ITEM_ID);
    }

    // ── The derivation ────────────────────────────────────────────────────────

    /**
     * Every method the interface puts in front of a caller that can remove a row or
     * overwrite an existing one.
     * <p>
     * Derived from {@link Class#getMethods()} — which for an interface returns the whole
     * inherited contract, so {@code CrudRepository}, {@code ListCrudRepository},
     * {@code PagingAndSortingRepository}, {@code QueryByExampleExecutor} and
     * {@code JpaRepository} all contribute and none of them has to be named here.
     * Bridge and synthetic methods are filtered out: a covariant override such as
     * {@code deleteById(Long)} over the erased {@code deleteById(Object)} makes javac emit
     * a second method, and counting it would double-count one refusal.
     */
    private static List<Method> mutatingMethodsOf(Class<?> repositoryInterface) {
        return Arrays.stream(repositoryInterface.getMethods())
                .filter(method -> !method.isBridge())
                .filter(method -> !method.isSynthetic())
                .filter(method -> !Modifier.isStatic(method.getModifiers()))
                .filter(method -> isMutating(method.getName()))
                .sorted(Comparator.comparing(Method::getName)
                        .thenComparing(method -> Arrays.toString(method.getParameterTypes())))
                .toList();
    }

    private static boolean isMutating(String name) {
        if (READ_NAME_PREFIXES.stream().anyMatch(name::startsWith)) {
            return false;
        }
        return !INSERT_PATH_NAMES.contains(name);
    }

    /**
     * The refusal for one mutating method, as this project implements it: a
     * {@code default} method declared <b>on the repository interface itself</b>.
     * <p>
     * Two conditions, both load-bearing. Declared on the interface, because a method that
     * is merely inherited is not a refusal this codebase owns. And {@code default}, because
     * that is what makes the proxy run this body instead of Spring Data's implementation.
     * A {@code default} method declared by Spring Data itself does not count either:
     * {@code JpaRepository.deleteInBatch} is exactly that — a default whose body delegates
     * to another method — and depending on somebody else's body is what WIKI4AI-172 is
     * about.
     */
    private static Optional<Method> refusalFor(Class<?> repositoryInterface, Method mutating) {
        return Arrays.stream(repositoryInterface.getDeclaredMethods())
                .filter(method -> !method.isBridge())
                .filter(method -> !method.isSynthetic())
                .filter(method -> method.getName().equals(mutating.getName()))
                .filter(method -> method.getParameterCount() == mutating.getParameterCount())
                .filter(Method::isDefault)
                .filter(method -> !isSpringData(method.getDeclaringClass()))
                .findFirst();
    }

    private static boolean isSpringData(Class<?> type) {
        return type.getName().startsWith("org.springframework.data.");
    }

    /** The mutating methods a given interface does NOT refuse. */
    private static List<Method> unrefused(Class<?> repositoryInterface) {
        return mutatingMethodsOf(repositoryInterface).stream()
                .filter(mutating -> refusalFor(repositoryInterface, mutating).isEmpty())
                .toList();
    }

    private static String signature(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(", ")) + ")";
    }

    // ── AC: the derived list, and that both repositories cover all of it ──────

    @Nested
    @DisplayName("AC: the mutating list is derived from the interface, and both repositories refuse it")
    class EveryMutatingMethodIsRefused {

        @Test
        @DisplayName("the derived list is 10 signatures, and deleteInBatch is one of them")
        void derivedListOfJpaRepository() {
            List<Method> mutating = mutatingMethodsOf(JpaRepository.class);

            assertThat(mutating)
                    .extracting(AppendOnlyRepositoryGuardTest::signature)
                    .as("derived at runtime from JpaRepository + its super-interfaces; "
                            + "10 signatures over 8 distinct names, because deleteAll and "
                            + "deleteAllInBatch each exist in a no-argument and an Iterable form")
                    .hasSize(10)
                    .containsExactlyInAnyOrder(
                            "delete(Object)",
                            "deleteById(Object)",
                            "deleteAll()",
                            "deleteAll(Iterable)",
                            "deleteAllById(Iterable)",
                            "deleteAllInBatch()",
                            "deleteAllInBatch(Iterable)",
                            "deleteInBatch(Iterable)",
                            "deleteAllByIdInBatch(Iterable)",
                            "saveAndFlush(Object)");

            assertThat(mutating).extracting(Method::getName)
                    .as("the trap this story exists for")
                    .contains("deleteInBatch");
        }

        @Test
        @DisplayName("the derivation keeps every read method out of the list")
        void readsAreNotMutating() {
            assertThat(mutatingMethodsOf(JpaRepository.class))
                    .extracting(Method::getName)
                    .as("a list that mixed in findAll or count would refuse reads and break the API")
                    .noneMatch(name -> READ_NAME_PREFIXES.stream().anyMatch(name::startsWith));

            assertThat(mutatingMethodsOf(JpaRepository.class))
                    .extracting(Method::getName)
                    .as("save/saveAll/saveAllAndFlush/flush are the insert path an append-only "
                            + "table exists to allow")
                    .doesNotContain("save", "saveAll", "saveAllAndFlush", "flush");
        }

        @Test
        @DisplayName("WorkItemEventRepository declares a refusal for all 10")
        void eventRepositoryRefusesEveryOne() {
            assertRefusesAll(WorkItemEventRepository.class);
        }

        @Test
        @DisplayName("WorkItemCommentRepository declares a refusal for all 10")
        void commentRepositoryRefusesEveryOne() {
            assertRefusesAll(WorkItemCommentRepository.class);
        }

        @Test
        @DisplayName("neither repository grew a @Query while the guards were being closed")
        void noQueryAnnotationAppeared() {
            assertThat(Arrays.stream(WorkItemEventRepository.class.getDeclaredMethods()))
                    .filteredOn(method -> method.isAnnotationPresent(Query.class))
                    .as("the payload TEXT of these two tables is where a LIKE would appear; two "
                            + "other tests guard the same rule and this one keeps it guarded")
                    .isEmpty();
            assertThat(Arrays.stream(WorkItemCommentRepository.class.getDeclaredMethods()))
                    .filteredOn(method -> method.isAnnotationPresent(Query.class))
                    .isEmpty();
        }

        private void assertRefusesAll(Class<?> repositoryInterface) {
            List<Method> mutating = mutatingMethodsOf(JpaRepository.class);
            List<String> missing = mutating.stream()
                    .filter(method -> refusalFor(repositoryInterface, method).isEmpty())
                    .map(AppendOnlyRepositoryGuardTest::signature)
                    .toList();

            assertThat(missing)
                    .as("%s inherits an unchanged mutating method from Spring Data".formatted(
                            repositoryInterface.getSimpleName()))
                    .isEmpty();

            assertThat(Arrays.stream(repositoryInterface.getDeclaredMethods())
                    .filter(method -> !method.isBridge() && !method.isSynthetic())
                    .filter(Method::isDefault)
                    .filter(method -> isMutating(method.getName()))
                    .map(AppendOnlyRepositoryGuardTest::signature))
                    .as("the refusals this interface owns")
                    .hasSize(10);
        }
    }

    // ── AC: the refusal is real at runtime, and the rows survive it ───────────

    @Nested
    @DisplayName("AC: every refusal actually runs, and the rows are still there afterwards")
    class RefusalsRunAndRowsSurvive {

        @Test
        @DisplayName("calling all 10 mutating methods on WorkItemEventRepository throws 10 times")
        void eventRepositoryRefusesAtRuntime() {
            insertEvent();
            WorkItemEvent sample = firstEvent();

            List<Method> refusals = refusalsOf(WorkItemEventRepository.class);
            assertThat(refusals).hasSize(10);

            for (Method refusal : refusals) {
                assertThatThrownBy(() -> invoke(refusal, workItemEventRepository, sample, 1L))
                        .as(signature(refusal))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("work_item_events is append-only");
            }
        }

        @Test
        @DisplayName("calling all 10 mutating methods on WorkItemCommentRepository throws 10 times")
        void commentRepositoryRefusesAtRuntime() {
            insertComment();
            WorkItemComment sample = firstComment();

            List<Method> refusals = refusalsOf(WorkItemCommentRepository.class);
            assertThat(refusals).hasSize(10);

            for (Method refusal : refusals) {
                assertThatThrownBy(() -> invoke(refusal, workItemCommentRepository, sample, 1L))
                        .as(signature(refusal))
                        .isInstanceOf(UnsupportedOperationException.class)
                        .hasMessageContaining("work_item_comments is immutable");
            }
        }

        /**
         * The method this story was written about, called directly rather than through the
         * derivation loop: {@code deleteInBatch(Iterable)} is the bulk statement that
         * bypasses {@code @PreRemove}.
         */
        @Test
        @DisplayName("deleteInBatch(Iterable) is refused explicitly, not by an inherited default")
        void deleteInBatchIsRefusedExplicitly() {
            insertEvent();
            WorkItemEvent sample = firstEvent();

            Method declared = refusalFor(WorkItemEventRepository.class,
                            mutatingMethodsOf(JpaRepository.class).stream()
                                    .filter(method -> method.getName().equals("deleteInBatch"))
                                    .findFirst()
                                    .orElseThrow())
                    .orElseThrow(() -> new AssertionError(
                            "WorkItemEventRepository does not declare deleteInBatch itself"));

            assertThat(declared.getDeclaringClass())
                    .as("the body that throws is this interface's, not JpaRepository's")
                    .isEqualTo(WorkItemEventRepository.class);

            assertThatThrownBy(() -> workItemEventRepository.deleteInBatch(List.of(sample)))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("DELETE IN BATCH");
        }

        /**
         * The part an exception alone cannot prove. A refusal that happened <i>after</i> the
         * {@code DELETE FROM} was sent would still throw, and the trail would still be gone.
         * So the row count is read from the database itself, before and after the whole
         * sweep of attempts.
         */
        @Test
        @DisplayName("SELECT COUNT(*) before and after every attempted bulk delete is the same number")
        void databaseIsUntouchedAfterEveryAttempt() {
            insertEvent();
            insertEvent();
            insertComment();
            insertComment();

            WorkItemEvent event = firstEvent();
            WorkItemComment comment = firstComment();

            int eventsBefore = count("work_item_events");
            int commentsBefore = count("work_item_comments");
            assertThat(eventsBefore).as("the count check is meaningless against an empty table")
                    .isGreaterThanOrEqualTo(2);
            assertThat(commentsBefore).isGreaterThanOrEqualTo(2);

            // The bulk statements specifically, then every other mutating method too.
            assertThatThrownBy(() -> workItemEventRepository.deleteInBatch(List.of(event)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemEventRepository.deleteAllInBatch())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemEventRepository.deleteAllInBatch(List.of(event)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemEventRepository.deleteAllByIdInBatch(List.of(event.getId())))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemCommentRepository.deleteInBatch(List.of(comment)))
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllInBatch())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> workItemCommentRepository.deleteAllByIdInBatch(List.of(comment.getId())))
                    .isInstanceOf(UnsupportedOperationException.class);

            for (Method refusal : refusalsOf(WorkItemEventRepository.class)) {
                assertThatThrownBy(() -> invoke(refusal, workItemEventRepository, event, event.getId()))
                        .as(signature(refusal))
                        .isInstanceOf(UnsupportedOperationException.class);
            }
            for (Method refusal : refusalsOf(WorkItemCommentRepository.class)) {
                assertThatThrownBy(() -> invoke(refusal, workItemCommentRepository, comment, comment.getId()))
                        .as(signature(refusal))
                        .isInstanceOf(UnsupportedOperationException.class);
            }

            assertThat(count("work_item_events"))
                    .as("after 27 attempted deletions the audit rows are still in the database")
                    .isEqualTo(eventsBefore);
            assertThat(count("work_item_comments"))
                    .as("and so are the comments")
                    .isEqualTo(commentsBefore);
        }
    }

    // ── The insert path is still open ─────────────────────────────────────────

    @Nested
    @DisplayName("AC: append-only still means append")
    class TheInsertPathIsStillOpen {

        @Test
        @DisplayName("save still writes an event, and saveAll still writes comments")
        void insertsStillWork() {
            WorkItemEvent event = WorkItemEvent.builder()
                    .workItemId(SENTINEL_ITEM_ID)
                    .event(WorkItemEvent.EVENT_CREATED)
                    .toStatus("backlog")
                    .createdAt(LocalDateTime.now())
                    .build();

            WorkItemEvent saved = workItemEventRepository.save(event);

            assertThat(saved.getId()).isNotNull();
            assertThat(count("work_item_events")).isGreaterThanOrEqualTo(1);

            List<WorkItemComment> comments = List.of(WorkItemComment.builder()
                    .workItemId(SENTINEL_ITEM_ID)
                    .kind(WorkItemComment.KIND_NOTE)
                    .body("first")
                    .createdAt(LocalDateTime.now())
                    .build());

            assertThat(workItemCommentRepository.saveAll(comments)).hasSize(1);
            assertThat(count("work_item_comments")).isGreaterThanOrEqualTo(1);
        }
    }

    // ── The detection itself is tested ────────────────────────────────────────

    @Nested
    @DisplayName("The guard test would notice a mutating method it does not yet know about")
    class TheDetectionItselfIsTested {

        /**
         * A mutating method of the kind a future Spring Data release — or a developer
         * adding a method to one of these interfaces — would bring. Nothing in this file
         * knows its name in advance.
         */
        interface FutureMutatingRepository extends JpaRepository<WorkItemEvent, Long> {
            void purgeAllForTest();
        }

        @Test
        @DisplayName("a brand-new mutating method is added to the derived list")
        void newMutatingMethodIsDerived() {
            List<String> fromJpaRepository = mutatingMethodsOf(JpaRepository.class).stream()
                    .map(AppendOnlyRepositoryGuardTest::signature)
                    .toList();
            List<String> fromProbe = mutatingMethodsOf(FutureMutatingRepository.class).stream()
                    .map(AppendOnlyRepositoryGuardTest::signature)
                    .toList();

            assertThat(fromProbe)
                    .as("the derivation reads the interface, so a method nobody listed here shows up")
                    .hasSize(fromJpaRepository.size() + 1)
                    .contains("purgeAllForTest()");
        }

        @Test
        @DisplayName("and the same check reports it as NOT refused — which is what fails the build")
        void newMutatingMethodIsReportedAsUnrefused() {
            assertThat(unrefused(FutureMutatingRepository.class))
                    .extracting(AppendOnlyRepositoryGuardTest::signature)
                    .as("an interface that inherits a mutating method without refusing it is "
                            + "reported, exactly as WorkItemEventRepository was reported for "
                            + "deleteInBatch before WIKI4AI-172")
                    .hasSize(11)
                    .contains("purgeAllForTest()");
        }

        @Test
        @DisplayName("applied to raw JpaRepository, the check reports all 10 as unrefused")
        void theCheckIsNotVacuouslyTrue() {
            assertThat(unrefused(JpaRepository.class))
                    .as("JpaRepository refuses nothing of its own, so if this assertion ever "
                            + "comes back empty the check has stopped detecting anything")
                    .hasSize(10);
        }

        @Test
        @DisplayName("a default method inherited from Spring Data does not count as a refusal")
        void inheritedDefaultIsNotAGuard() {
            Method deleteInBatch = Arrays.stream(JpaRepository.class.getMethods())
                    .filter(method -> !method.isBridge() && !method.isSynthetic())
                    .filter(method -> method.getName().equals("deleteInBatch"))
                    .findFirst()
                    .orElseThrow();

            assertThat(deleteInBatch.isDefault())
                    .as("Spring Data 3.2 declares deleteInBatch as a default that delegates to "
                            + "deleteAllInBatch — the reason the old refusal looked complete")
                    .isTrue();

            assertThat(refusalFor(JpaRepository.class, deleteInBatch))
                    .as("and a guard test that accepted an inherited default would have passed "
                            + "while the audit trail was one Spring Data release away from being "
                            + "deletable")
                    .isEmpty();

            Optional<Method> ownRefusal = refusalFor(WorkItemEventRepository.class, deleteInBatch);

            assertThat(ownRefusal)
                    .as("here the body is this interface's own")
                    .isPresent();
            assertThat(ownRefusal.orElseThrow().getDeclaringClass())
                    .isEqualTo(WorkItemEventRepository.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private List<Method> refusalsOf(Class<?> repositoryInterface) {
        return mutatingMethodsOf(JpaRepository.class).stream()
                .map(mutating -> refusalFor(repositoryInterface, mutating)
                        .orElseThrow(() -> new AssertionError(
                                repositoryInterface.getSimpleName() + " does not refuse "
                                        + signature(mutating))))
                .toList();
    }

    /**
     * Invoke a refusal with arguments built from its own generic signature: an
     * {@code Iterable<Long>} gets ids, an {@code Iterable<WorkItemEvent>} gets entities, a
     * bare entity parameter gets the entity. Nothing in this helper knows a method name.
     */
    private static Object[] argumentsFor(Method refusal, Object entity, Long id) {
        Type[] generics = refusal.getGenericParameterTypes();
        Class<?>[] erased = refusal.getParameterTypes();
        Object[] arguments = new Object[erased.length];

        for (int i = 0; i < erased.length; i++) {
            Class<?> elementType = erased[i] == Iterable.class ? elementTypeOf(generics[i]) : erased[i];

            if (elementType != null && elementType.isInstance(entity)) {
                arguments[i] = erased[i] == Iterable.class ? List.of(entity) : entity;
            } else if (elementType != null && (elementType == Long.class || elementType == Object.class)) {
                arguments[i] = erased[i] == Iterable.class ? List.of(id) : id;
            } else {
                throw new AssertionError("No sample argument for " + signature(refusal)
                        + " — the derivation has met a parameter shape it does not handle");
            }
        }
        return arguments;
    }

    private static Class<?> elementTypeOf(Type generic) {
        if (generic instanceof ParameterizedType parameterized) {
            Type[] typeArguments = parameterized.getActualTypeArguments();
            if (typeArguments.length == 0) {
                return Object.class;
            }
            Type argument = typeArguments[0];
            if (argument instanceof Class<?> type) {
                return type;
            }
            if (argument instanceof WildcardType wildcard && wildcard.getUpperBounds().length > 0
                    && wildcard.getUpperBounds()[0] instanceof Class<?> type) {
                return type;
            }
        }
        return Object.class;
    }

    private static void invoke(Method refusal, Object repository, Object entity, Long id) throws Exception {
        try {
            refusal.invoke(repository, argumentsFor(refusal, entity, id));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception exception ? exception : e;
        }
    }

    private void insertEvent() {
        jdbcTemplate.update("INSERT INTO work_item_events (work_item_id, event, to_status, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                SENTINEL_ITEM_ID, WorkItemEvent.EVENT_CREATED, "backlog", LocalDateTime.now());
    }

    private void insertComment() {
        jdbcTemplate.update("INSERT INTO work_item_comments (work_item_id, kind, body, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                SENTINEL_ITEM_ID, WorkItemComment.KIND_NOTE, "guard test row", LocalDateTime.now());
    }

    private WorkItemEvent firstEvent() {
        return jdbcTemplate.queryForObject(
                "SELECT id, work_item_id, event, to_status, created_at FROM work_item_events "
                        + "WHERE work_item_id = ? ORDER BY id LIMIT 1",
                (rs, rowNum) -> WorkItemEvent.builder()
                        .id(rs.getLong("id"))
                        .workItemId(rs.getLong("work_item_id"))
                        .event(rs.getString("event"))
                        .toStatus(rs.getString("to_status"))
                        .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                        .build(),
                SENTINEL_ITEM_ID);
    }

    private WorkItemComment firstComment() {
        return jdbcTemplate.queryForObject(
                "SELECT id, work_item_id, kind, body, created_at FROM work_item_comments "
                        + "WHERE work_item_id = ? ORDER BY id LIMIT 1",
                (rs, rowNum) -> WorkItemComment.builder()
                        .id(rs.getLong("id"))
                        .workItemId(rs.getLong("work_item_id"))
                        .kind(rs.getString("kind"))
                        .body(rs.getString("body"))
                        .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                        .build(),
                SENTINEL_ITEM_ID);
    }

    private int count(String table) {
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
