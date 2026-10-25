package com.wiki4ai.migration;

import com.wiki4ai.model.Actor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-153 and WIKI4AI-154: assertions on the V23 and V24 migration files.
 * <p>
 * Tests run on H2 with Flyway disabled — the schema is built by Hibernate from the entities
 * (SPEC rule 5) — so the SQL of V23 and V24 is never executed locally, and a mistake in it is
 * found on dev .219, where the postgres profile runs with {@code ddl-auto: validate} plus
 * Flyway and a mismatch stops the backend at startup. That is why these assertions read the
 * files: they are the only place their text is checked before it is deployed. They are read
 * from the classpath, not from a source path, so they hold wherever the tests run.
 * <p>
 * They do not execute the files. V24 is PostgreSQL-shaped ({@code CREATE INDEX IF NOT EXISTS}
 * on tables that only exist once V20..V22 have run) and running it against an H2 schema built
 * by Hibernate would fail for reasons that say nothing about V24 being correct.
 *
 * <h2>The allocation, because it is the part a later story can break</h2>
 * V1..V22 were taken. WIKI4AI-153 got <b>V23</b>, WIKI4AI-154 got <b>V24</b>, and <b>V25 is
 * reserved</b> for the resources and reservations of WIKI4AI-155..157. An existing migration
 * is never rewritten: a Flyway version that has run on .219 is already in {@code flyway_schema_history},
 * and changing its text makes the next start fail on a checksum.
 */
@DisplayName("V23 + V24: the inbox marker and the indexes that make the inbox cheap")
class InboxMigrationTest {

    private static final String V20 = "db/migration/V20__create_work_items.sql";
    private static final String V23 = "db/migration/V23__add_actor_inbox_marker.sql";
    private static final String V24 = "db/migration/V24__create_inbox_indexes.sql";

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    private static String read(String resource) {
        try (InputStream stream = InboxMigrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("%s must be on the classpath", resource)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    /**
     * The file with its comments removed.
     * <p>
     * The negative assertions have to run on this, not on the raw text: V24 explains itself,
     * and its explanation contains the name of the index it deliberately does not create.
     * Asserting on the raw file would fail a correct migration for quoting its own reasoning.
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

    // ── AC 153: V23 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("V23: the marker column")
    class MarkerMigration {

        @Test
        @DisplayName("V23 adds last_seen_event_id to actors as a nullable BIGINT")
        void addsNullableBigintToActors() {
            List<String> statements = statements(read(V23));

            assertThat(statements)
                    .as("V23 is one statement and nothing else: a column, no backfill, no"
                            + " constraint, no index")
                    .hasSize(1);
            assertThat(statements.get(0))
                    .isEqualTo("ALTER TABLE actors ADD COLUMN last_seen_event_id BIGINT");
        }

        @Test
        @DisplayName("no NOT NULL, no DEFAULT: NULL has to mean 'this actor never read its inbox'")
        void columnHasNoNullConstraintAndNoDefault() {
            String sql = executableSql(read(V23));

            assertThat(sql)
                    .as("a NOT NULL would force a value, and the only value available is 0 — a"
                            + " position in a sequence whose ids start at 1, invented to mean"
                            + " 'nothing yet'")
                    .doesNotContain("NOT NULL");
            assertThat(sql)
                    .doesNotContain("DEFAULT");
            assertThat(sql)
                    .doesNotContain("CHECK");
        }

        @Test
        @DisplayName("V23 touches no table but actors, and adds no index")
        void touchesOnlyActors() {
            String sql = executableSql(read(V23));

            assertThat(sql).containsIgnoringCase("ALTER TABLE actors");
            assertThat(statements(read(V23)).size()).isEqualTo(1);
            assertThat(sql).doesNotContainIgnoringCase("CREATE INDEX");
            assertThat(sql).doesNotContainIgnoringCase("CREATE TABLE");
        }

        @Test
        @DisplayName("V23 uses no TIMESTAMPTZ (SPEC rule 11b)")
        void usesNoTimestamptz() {
            assertThat(executableSql(read(V23)))
                    .as("TIMESTAMPTZ maps to java.time.Instant, and the postgres profile runs"
                            + " with ddl-auto: validate against java.time types")
                    .doesNotContainIgnoringCase("TIMESTAMPTZ");
        }

        @Test
        @DisplayName("the Actor entity carries the field as a Long, and nothing else about Actor changed")
        void actorEntityCarriesTheField() throws Exception {
            Field field = Actor.class.getDeclaredField("lastSeenEventId");

            assertThat(field.getType())
                    .as("Long, not long: a primitive cannot hold the NULL that V23 leaves there")
                    .isEqualTo(Long.class);
            assertThat(field.isAnnotationPresent(jakarta.persistence.Column.class)).isTrue();

            jakarta.persistence.Column column = field.getAnnotation(jakarta.persistence.Column.class);
            assertThat(column.name()).isEqualTo("last_seen_event_id");
            assertThat(column.nullable())
                    .as("the entity must not demand more than the column does")
                    .isTrue();

            assertThat(Actor.class.getDeclaredConstructors())
                    .as("no constructor signature was repurposed for this field")
                    .anyMatch(constructor -> constructor.getParameterCount() == 0);
        }
    }

    // ── AC 154: V24 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("V24: the three indexes")
    class IndexMigration {

        @Test
        @DisplayName("V24 creates exactly idx_wic_item_id, idx_wie_item_id and idx_wie_actor_id")
        void createsExactlyTheThreeIndexes() {
            List<String> statements = statements(read(V24));

            assertThat(statements).hasSize(3);
            assertThat(statements).containsExactlyInAnyOrder(
                    "CREATE INDEX IF NOT EXISTS idx_wic_item_id ON work_item_comments (work_item_id, id)",
                    "CREATE INDEX IF NOT EXISTS idx_wie_item_id ON work_item_events (work_item_id, id)",
                    "CREATE INDEX IF NOT EXISTS idx_wie_actor_id ON work_item_events (actor_id, id)");
        }

        @Test
        @DisplayName("each index is (lookup column, id) — the pair a keyset read needs")
        void everyIndexEndsInId() {
            Matcher matcher = Pattern.compile("ON\\s+(\\w+)\\s*\\(([^)]*)\\)")
                    .matcher(executableSql(read(V24)));

            List<String> columns = new ArrayList<>();
            while (matcher.find()) {
                columns.add(matcher.group(2).replaceAll("\\s+", ""));
            }

            assertThat(columns).hasSize(3);
            assertThat(columns).allSatisfy(pair ->
                    assertThat(pair)
                            .as("a keyset read is 'WHERE scope = ? AND id > ? ORDER BY id'; an"
                                    + " index on the scope column alone would still have to sort")
                            .endsWith(",id"));
        }

        @Test
        @DisplayName("V24 does NOT create idx_work_items_assignee — V20 already did")
        void doesNotDuplicateTheAssigneeIndex() {
            String v24 = executableSql(read(V24));

            assertThat(v24)
                    .as("the index that serves the 'items assigned to me' stream was shipped"
                            + " with WIKI4AI-140; a second index on the same column is write"
                            + " cost bought twice for no read benefit")
                    .doesNotContain("idx_work_items_assignee");
            assertThat(v24)
                    .as("and V24 does not touch work_items at all")
                    .doesNotContain("work_items");

            // The claim only means something if the index really is elsewhere.
            assertThat(read(V20))
                    .as("V20__create_work_items.sql is where idx_work_items_assignee lives")
                    .contains("CREATE INDEX idx_work_items_assignee ON work_items (assignee_actor_id, status)");
        }

        @Test
        @DisplayName("V24 creates no other index and no table")
        void createsNothingElse() {
            String sql = executableSql(read(V24));

            assertThat(countOccurrences(sql.toUpperCase(), "CREATE INDEX")).isEqualTo(3);
            assertThat(sql).doesNotContainIgnoringCase("CREATE TABLE");
            assertThat(sql).doesNotContainIgnoringCase("DROP");
            assertThat(sql).doesNotContainIgnoringCase("ALTER TABLE");
        }

        @Test
        @DisplayName("V24 uses no TIMESTAMPTZ and no timestamp column at all (SPEC rule 11b)")
        void usesNoTimestamptz() {
            String sql = executableSql(read(V24));

            assertThat(sql).doesNotContainIgnoringCase("TIMESTAMPTZ");
            assertThat(sql)
                    .as("the inbox indexes are on id columns; nothing here can drift into a"
                            + " timezone-carrying type")
                    .doesNotContainIgnoringCase("TIMESTAMP");
        }

        @Test
        @DisplayName("the indexes are on the two append-only tables and on comments, which is"
                + " what the inbox reads")
        void indexesCoverTheInboxReads() {
            String sql = executableSql(read(V24));

            assertThat(sql).contains("work_item_comments");
            assertThat(sql).contains("work_item_events");
            assertThat(sql).contains("actor_id");
        }

        private int countOccurrences(String haystack, String needle) {
            int count = 0;
            int index = haystack.indexOf(needle);
            while (index >= 0) {
                count++;
                index = haystack.indexOf(needle, index + needle.length());
            }
            return count;
        }
    }

    // ── The allocation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The migration allocation")
    class Allocation {

        @Test
        @DisplayName("no version number is used twice, and every file this story reads exists")
        void migrationVersionsAreUnique() {
            List<String> versions = new ArrayList<>();
            for (String resource : List.of("V1__create_schema", "V2__add_role_column",
                    "V3__create_refresh_tokens_table", "V4__make_expires_at_nullable",
                    "V5__create_api_tokens_table", "V6__create_vault_entries_table",
                    "V7__add_vault_master_password_hash", "V8__add_vault_salt",
                    "V9__add_project_parent_hierarchy", "V10__add_document_embeddings",
                    "V11__add_document_version", "V12__add_user_language",
                    "V13__create_calendar", "V14__add_visibility", "V15__create_actors",
                    "V16__create_project_actors", "V17__actor_presence",
                    "V18__rework_api_tokens", "V19__add_actor_attribution_to_documents",
                    "V20__create_work_items", "V21__create_work_item_events",
                    "V22__create_work_item_comments", "V23__add_actor_inbox_marker",
                    "V24__create_inbox_indexes")) {
                read("db/migration/" + resource + ".sql");
                Matcher matcher = VERSION.matcher(resource);
                assertThat(matcher.find())
                        .as("%s is named V<n>__<description>", resource)
                        .isTrue();
                versions.add(matcher.group(1));
            }

            Set<String> unique = new HashSet<>(versions);
            assertThat(unique)
                    .as("a Flyway version that has already run on .219 is in"
                            + " flyway_schema_history; reusing its number, or editing the text"
                            + " of one that ran, fails the next start on a checksum")
                    .hasSize(versions.size());
        }

        @Test
        @DisplayName("V23 and V24 are the two new files, and V25 is left alone")
        void v25IsNotUsedByThisStory() {
            assertThat(InboxMigrationTest.class.getClassLoader()
                    .getResource("db/migration/V25__add_actor_inbox_marker.sql")).isNull();
            assertThat(InboxMigrationTest.class.getClassLoader()
                    .getResource("db/migration/V25__create_inbox_indexes.sql")).isNull();

            // V23 and V24 exist under their own names.
            assertThat(read(V23)).isNotBlank();
            assertThat(read(V24)).isNotBlank();
        }

        @Test
        @DisplayName("V22's indexes are still there — V24 adds to them, it does not replace them")
        void doesNotTouchEarlierMigrations() {
            assertThat(read("db/migration/V22__create_work_item_comments.sql"))
                    .contains("CREATE INDEX");
            assertThat(read("db/migration/V21__create_work_item_events.sql"))
                    .contains("CREATE INDEX");
        }
    }
}
