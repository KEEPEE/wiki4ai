package com.wiki4ai.migration;

import com.wiki4ai.model.Reservation;
import com.wiki4ai.model.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-155..157: assertions on the V25 migration file.
 * <p>
 * Tests run on H2 with Flyway disabled — the schema is built by Hibernate from the entities
 * (SPEC rule 5) — so the SQL of V25 is never executed locally, and a mistake in it is found
 * on dev .219, where the postgres profile runs with {@code ddl-auto: validate} plus Flyway
 * and a mismatch stops the backend at startup. That is why these assertions read the file:
 * it is the only place its text is checked before it is deployed. They are read from the
 * classpath, not from a source path, so they hold wherever the tests run.
 *
 * <h2>The allocation, because it is the part a later story can break</h2>
 * V1..V24 were taken. WIKI4AI-155 got <b>V25</b>. V26 is reserved and must not appear.
 * An existing migration is never rewritten: a Flyway version that has already run on .219 is
 * in {@code flyway_schema_history}, and changing its text makes the next start fail on a
 * checksum. The assertions below therefore check both that V25 exists and that V1..V24 still
 * say what they were written to say.
 */
@DisplayName("V25: resources + reservations, and the migrations around it untouched")
class ResourcesMigrationTest {

    private static final String V25 = "db/migration/V25__create_resources_and_reservations.sql";
    private static final String V20 = "db/migration/V20__create_work_items.sql";
    private static final String V21 = "db/migration/V21__create_work_item_events.sql";
    private static final String V22 = "db/migration/V22__create_work_item_comments.sql";
    private static final String V23 = "db/migration/V23__add_actor_inbox_marker.sql";
    private static final String V24 = "db/migration/V24__create_inbox_indexes.sql";

    /** The versions this project has allocated. V25 is this story; V26 is reserved. */
    private static final List<String> ALLOCATED = List.of(
            "V1__create_schema",
            "V2__add_role_column",
            "V3__create_refresh_tokens_table",
            "V4__make_expires_at_nullable",
            "V5__create_api_tokens_table",
            "V6__create_vault_entries_table",
            "V7__add_vault_master_password_hash",
            "V8__add_vault_salt",
            "V9__add_project_parent_hierarchy",
            "V10__add_document_embeddings",
            "V11__add_document_version",
            "V12__add_user_language",
            "V13__create_calendar",
            "V14__add_visibility",
            "V15__create_actors",
            "V16__create_project_actors",
            "V17__actor_presence",
            "V18__rework_api_tokens",
            "V19__add_actor_attribution_to_documents",
            "V20__create_work_items",
            "V21__create_work_item_events",
            "V22__create_work_item_comments",
            "V23__add_actor_inbox_marker",
            "V24__create_inbox_indexes",
            "V25__create_resources_and_reservations");

    private static String read(String resource) {
        try (InputStream stream = ResourcesMigrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("%s must be on the classpath", resource)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    private static boolean exists(String resource) {
        try (InputStream stream = ResourcesMigrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            return stream != null;
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    /**
     * The file with its comments removed.
     * <p>
     * The negative assertions have to run on this, not on the raw text: V25 explains itself,
     * and its explanation quotes the very words it forbids. Asserting on the raw file would
     * fail a correct migration for quoting its own reasoning.
     */
    private static String executableSql(String sql) {
        String withoutLineComments = sql.replaceAll("(?m)--[^\\n]*", "");
        return withoutLineComments.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    /** The statements a migration hands to the database, in order. */
    private static List<String> statements(String sql) {
        List<String> statements = new ArrayList<>();
        for (String part : executableSql(sql).split(";")) {
            String statement = part.trim();
            if (!statement.isEmpty()) {
                statements.add(statement.replaceAll("\\s+", " "));
            }
        }
        return statements;
    }

    private static List<String> matching(List<String> statements, String pattern) {
        Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        for (String statement : statements) {
            if (compiled.matcher(statement).find()) {
                hits.add(statement);
            }
        }
        return hits;
    }

    @Nested
    @DisplayName("V25 exists and is the resources + reservations migration")
    class Exists {

        @Test
        void v25IsOnTheClasspath() {
            assertThat(exists(V25)).as("V25__create_resources_and_reservations.sql").isTrue();
        }

        @Test
        void v25CreatesExactlyTwoTables() {
            List<String> creates = matching(statements(read(V25)), "^\\s*CREATE TABLE");
            assertThat(creates).hasSize(2);
            assertThat(creates).anyMatch(s -> s.contains("resources"));
            assertThat(creates).anyMatch(s -> s.contains("reservations"));
        }

        @Test
        void v25IsNotNamedV20OrAnyOtherTakenVersion() {
            // WIKI4AI-155's Jira label says "Migracia V20". V20 is work_items and shipped
            // with WIKI4AI-140; the label is stale, the allocation is not negotiable.
            assertThat(exists("db/migration/V20__create_work_items.sql")).isTrue();
            assertThat(ALLOCATED).contains("V25__create_resources_and_reservations");
        }
    }

    @Nested
    @DisplayName("The CHECK constraints that hold the invariant in the database")
    class Constraints {

        @Test
        void v25DeclaresFourCheckConstraints() {
            // Counted as occurrences, not as statements: all four live inside the two
            // CREATE TABLE statements, so a per-statement count would say 2.
            String sql = executableSql(read(V25));
            assertThat(Pattern.compile("CHECK\\s*\\(").matcher(sql).results().count())
                    .as("capacity >= 1, active_slots >= 0, active_slots <= capacity, slots >= 1")
                    .isEqualTo(4);
        }

        @Test
        void theCapacityInvariantIsDeclaredOnResources() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("capacity\\s*>=\\s*1");
            assertThat(sql).containsPattern("active_slots\\s*>=\\s*0");
            assertThat(sql).containsPattern("active_slots\\s*<=\\s*capacity");
        }

        @Test
        void aReservationMustHoldAtLeastOneSlot() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("slots\\s*>=\\s*1");
        }

        @Test
        void theConstraintsAreNamedSoAnErrorSaysWhichOneFailed() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("CONSTRAINT\\s+\\w+\\s+CHECK");
            assertThat(Pattern.compile("CONSTRAINT\\s+\\w+\\s+CHECK").matcher(sql).results().count())
                    .isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("The indexes the two access patterns need")
    class Indexes {

        @Test
        void v25CreatesExactlyTwoIndexes() {
            List<String> indexes = matching(statements(read(V25)), "^\\s*CREATE INDEX");
            assertThat(indexes).hasSize(2);
        }

        @Test
        void theIndexesMatchTheTwoQueriesThisFeatureRuns() {
            String sql = executableSql(read(V25));
            // "unreleased reservations of one resource" — the per-resource listing.
            assertThat(sql).contains("idx_reservations_resource_active");
            assertThat(sql).containsPattern("idx_reservations_resource_active\\s+ON\\s+reservations\\s*\\(\\s*resource_id\\s*,\\s*released_at\\s*\\)");
            // "unreleased reservations whose expiry is in the past" — the TTL sweep.
            assertThat(sql).contains("idx_reservations_expiry");
            assertThat(sql).containsPattern("idx_reservations_expiry\\s+ON\\s+reservations\\s*\\(\\s*expires_at\\s*\\)");
        }
    }

    @Nested
    @DisplayName("Time columns and the shape of the schema")
    class TimeColumns {

        @Test
        void v25UsesTimestampWithoutTimeZoneAndNeverTimestamptz() {
            String sql = executableSql(read(V25));
            assertThat(sql).doesNotContain("TIMESTAMPTZ");
            assertThat(sql).doesNotContain("TIME ZONE");
            assertThat(Pattern.compile("TIMESTAMP").matcher(sql).results().count())
                    .as("resources.created_at, reservations.expires_at, reservations.released_at, reservations.created_at")
                    .isEqualTo(4);
        }

        @Test
        void releasedAtHasNoDefaultSoNullMeansLive() {
            List<String> statements = statements(read(V25));
            for (String statement : statements) {
                if (statement.contains("released_at")) {
                    assertThat(statement)
                            .as("a DEFAULT on released_at would make 'live' unrepresentable")
                            .doesNotContainPattern("released_at[^,]*DEFAULT");
                }
            }
        }

        @Test
        void expiresAtIsRequiredBecauseAReservationWithoutATtlWouldNeverEnd() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("expires_at\\s+TIMESTAMP\\s+NOT\\s+NULL");
        }

        @Test
        void theForeignKeyActionsAreCascadeForResourceAndSetNullForActorAndWorkItem() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("resource_id\\s+BIGINT\\s+NOT\\s+NULL\\s+REFERENCES\\s+resources\\s*\\(\\s*id\\s*\\)\\s+ON\\s+DELETE\\s+CASCADE");
            assertThat(sql).containsPattern("actor_id\\s+BIGINT\\s+REFERENCES\\s+actors\\s*\\(\\s*id\\s*\\)\\s+ON\\s+DELETE\\s+SET\\s+NULL");
            assertThat(sql).containsPattern("work_item_id\\s+BIGINT\\s+REFERENCES\\s+work_items\\s*\\(\\s*id\\s*\\)\\s+ON\\s+DELETE\\s+SET\\s+NULL");
        }

        @Test
        void keyIsUniqueAndNotNull() {
            String sql = executableSql(read(V25));
            assertThat(sql).containsPattern("key\\s+VARCHAR\\(64\\)\\s+NOT\\s+NULL\\s+UNIQUE");
        }

        /**
         * SPEC rule 11b is a two-sided rule: TIMESTAMP in the migration and LocalDateTime in
         * the entity. A {@code java.time.Instant} field maps to TIMESTAMP WITH TIME ZONE and
         * the postgres profile ({@code ddl-auto: validate}) would refuse to start.
         */
        @Test
        void theEntitiesMapEveryTimeColumnToLocalDateTimeAndNeverToInstant() {
            for (Class<?> type : List.of(Resource.class, Reservation.class)) {
                List<Field> timeFields = new ArrayList<>();
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() == LocalDateTime.class) {
                        timeFields.add(field);
                    }
                    assertThat(field.getType())
                            .as("%s.%s must not be an Instant", type.getSimpleName(), field.getName())
                            .isNotEqualTo(java.time.Instant.class);
                }
                if (type == Resource.class) {
                    assertThat(timeFields).extracting(Field::getName).containsExactly("createdAt");
                } else {
                    assertThat(timeFields).extracting(Field::getName)
                            .contains("expiresAt", "releasedAt", "createdAt");
                }
            }
        }
    }

    @Nested
    @DisplayName("The allocation around V25")
    class Allocation {

        @Test
        void everyAllocatedMigrationUpToV25IsOnTheClasspath() {
            for (String name : ALLOCATED) {
                assertThat(exists("db/migration/" + name + ".sql"))
                        .as(name)
                        .isTrue();
            }
        }

        @Test
        void v26IsNotUsed() {
            // V26 is reserved. A migration named V26__* would take a version a later story
            // has been told to expect. The classpath is not a directory listing, so the
            // reserved names are probed directly.
            assertThat(exists("db/migration/V26__create_resources_and_reservations.sql")).isFalse();
            assertThat(exists("db/migration/V26.sql")).isFalse();
            assertThat(exists("db/migration/V26__resources.sql")).isFalse();
            assertThat(exists("db/migration/V26__create_resources.sql")).isFalse();
            assertThat(exists("db/migration/V26__create_reservations.sql")).isFalse();
        }

        @Test
        void noMigrationIsNumberedAfterV25() {
            Pattern compiled = Pattern.compile("^V(\\d+)__");
            for (String name : ALLOCATED) {
                Matcher matcher = compiled.matcher(name);
                assertThat(matcher.find()).isTrue();
                assertThat(Integer.parseInt(matcher.group(1))).isLessThanOrEqualTo(25);
            }
        }

        /**
         * "Never rewrite an existing migration" is the rule that keeps .219 bootable: Flyway
         * has already recorded V20..V24 in {@code flyway_schema_history} and a changed text
         * fails the next start on a checksum. These assertions do not compare hashes — they
         * assert that each version still creates the thing it was allocated for, which is the
         * part a later story can accidentally move.
         */
        @Test
        void v20ThroughV24StillSayWhatTheyWereAllocatedFor() {
            assertThat(executableSql(read(V20))).contains("CREATE TABLE work_items");
            assertThat(executableSql(read(V21))).contains("CREATE TABLE work_item_events");
            assertThat(executableSql(read(V22))).contains("CREATE TABLE work_item_comments");
            assertThat(executableSql(read(V23))).contains("last_seen_event_id");
            assertThat(executableSql(read(V24))).contains("idx_wic_item_id");
            assertThat(executableSql(read(V24))).contains("idx_wie_item_id");
            assertThat(executableSql(read(V24))).contains("idx_wie_actor_id");
        }

        @Test
        void v25DoesNotTouchAnyTableThatAlreadyExists() {
            String sql = executableSql(read(V25));
            assertThat(sql).doesNotContain("ALTER TABLE");
            assertThat(sql).doesNotContain("DROP TABLE");
            assertThat(sql).doesNotContain("DROP");
        }
    }
}
