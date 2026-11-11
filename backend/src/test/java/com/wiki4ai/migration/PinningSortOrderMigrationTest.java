package com.wiki4ai.migration;

import com.wiki4ai.model.Document;
import com.wiki4ai.model.Project;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-184: assertions on the V26 migration file.
 * <p>
 * Same reasoning as {@link ResourcesMigrationTest}: tests run on H2 with Flyway disabled
 * and the schema is built by Hibernate from the entities, so the SQL of V26 is never
 * executed locally. Its text is therefore asserted here — it is the only place it is
 * checked before it runs on dev .219, where the postgres profile combines Flyway with
 * {@code ddl-auto: validate} and a mismatch between this file and the entities stops the
 * backend at startup.
 *
 * <h2>What this migration must never become</h2>
 * It is purely additive: two columns per table, both {@code NOT NULL} with a constant
 * default, plus two indexes. A {@code CREATE TABLE} would mean the story invented a new
 * table nobody asked for; a {@code DROP} would destroy data on a populated production
 * database; a nullable column without a default would make {@code boolean}/{@code int}
 * on the entities unable to represent the NULL the row carries.
 */
@DisplayName("V26: pinned + sort_order on projects and documents")
class PinningSortOrderMigrationTest {

    private static final String V26 = "db/migration/V26__add_pinning_and_sort_order.sql";
    private static final String V25 = "db/migration/V25__create_resources_and_reservations.sql";
    private static final String V1 = "db/migration/V1__create_schema.sql";

    private static String read(String resource) {
        try (InputStream stream = PinningSortOrderMigrationTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream)
                    .as("%s must be on the classpath", resource)
                    .isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + resource, e);
        }
    }

    /** The file with its comments removed — see ResourcesMigrationTest for why. */
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

    private static List<String> matching(String sql, String pattern) {
        Pattern compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        List<String> hits = new ArrayList<>();
        for (String statement : statements(sql)) {
            if (compiled.matcher(statement).find()) {
                hits.add(statement);
            }
        }
        return hits;
    }

    @Nested
    @DisplayName("The four columns")
    class Columns {

        @Test
        void v26AddsExactlyFourColumnsAndNothingElse() {
            String sql = executableSql(read(V26));
            List<String> alters = matching(sql, "^\\s*ALTER TABLE");
            assertThat(alters).as("pinned + sort_order on projects and on documents").hasSize(4);
            assertThat(alters).allMatch(s -> s.contains("ADD COLUMN"));
        }

        @Test
        void bothTablesGetPinnedAsNotNullBooleanDefaultingToFalse() {
            String sql = executableSql(read(V26));
            for (String table : List.of("projects", "documents")) {
                assertThat(sql).containsPattern(
                        "ALTER TABLE\\s+" + table + "\\s+ADD COLUMN\\s+pinned\\s+BOOLEAN\\s+NOT\\s+NULL\\s+DEFAULT\\s+FALSE");
            }
        }

        @Test
        void bothTablesGetSortOrderAsNotNullIntegerDefaultingToZero() {
            String sql = executableSql(read(V26));
            for (String table : List.of("projects", "documents")) {
                assertThat(sql).containsPattern(
                        "ALTER TABLE\\s+" + table + "\\s+ADD COLUMN\\s+sort_order\\s+INTEGER\\s+NOT\\s+NULL\\s+DEFAULT\\s+0");
            }
        }

        /**
         * The columns are primitives on the entities, which is only sound while the schema
         * cannot hold NULL. A nullable column would make an existing row unmappable.
         */
        @Test
        void theEntitiesMapBothColumnsAsNotNullPrimitives() throws NoSuchFieldException {
            for (Class<?> type : List.of(Project.class, Document.class)) {
                Field pinned = type.getDeclaredField("pinned");
                Field sortOrder = type.getDeclaredField("sortOrder");
                assertThat(pinned.getType()).isEqualTo(boolean.class);
                assertThat(sortOrder.getType()).isEqualTo(int.class);
                assertThat(pinned.getAnnotation(Column.class).nullable()).isFalse();
                assertThat(sortOrder.getAnnotation(Column.class).nullable()).isFalse();
                assertThat(pinned.getAnnotation(Column.class).name()).isEqualTo("pinned");
                assertThat(sortOrder.getAnnotation(Column.class).name()).isEqualTo("sort_order");
            }
        }

        @Test
        void v26AddsNoTableAndDropsNothing() {
            String sql = executableSql(read(V26));
            assertThat(matching(sql, "^\\s*CREATE TABLE")).isEmpty();
            assertThat(sql).doesNotContain("DROP");
            assertThat(sql).doesNotContain("TRUNCATE");
        }

        @Test
        void v26AddsNoTimeColumnSoRuleElevenBIsNotAtRisk() {
            String sql = executableSql(read(V26));
            assertThat(sql).doesNotContain("TIMESTAMP");
            assertThat(sql).doesNotContain("TIMESTAMPTZ");
        }
    }

    @Nested
    @DisplayName("The backfill, because a list order must survive the upgrade")
    class Backfill {

        @Test
        void projectsAreBackfilledByCreatedAtDescWithIdAsTieBreak() {
            String sql = executableSql(read(V26));
            assertThat(sql).containsPattern(
                    "ROW_NUMBER\\(\\)\\s+OVER\\s*\\(\\s*ORDER BY\\s+created_at\\s+DESC\\s*,\\s*id\\s+DESC\\s*\\)\\s*-\\s*1");
            assertThat(sql).containsPattern("UPDATE\\s+projects\\s+p\\s+SET\\s+sort_order");
        }

        @Test
        void documentsAreBackfilledPerProjectByUpdatedAtDescWithIdAsTieBreak() {
            String sql = executableSql(read(V26));
            assertThat(sql).containsPattern(
                    "PARTITION BY\\s+project_id\\s+ORDER BY\\s+updated_at\\s+DESC\\s*,\\s*id\\s+DESC");
            assertThat(sql).containsPattern("UPDATE\\s+documents\\s+d\\s+SET\\s+sort_order");
        }

        /**
         * Without a tie-break the window is non-deterministic for rows that share a
         * timestamp, and the same database would come back with a different manual order
         * on every run of the migration.
         */
        @Test
        void everyWindowIsFullyDeterministic() {
            for (String statement : statements(executableSql(read(V26)))) {
                if (statement.contains("ROW_NUMBER()")) {
                    assertThat(statement).contains("id DESC");
                }
            }
        }
    }

    @Nested
    @DisplayName("The indexes the two list queries need")
    class Indexes {

        @Test
        void v26CreatesExactlyTwoIndexes() {
            assertThat(matching(executableSql(read(V26)), "^\\s*CREATE INDEX")).hasSize(2);
        }

        @Test
        void theProjectIndexMatchesTheOrderOfTheProjectList() {
            String sql = executableSql(read(V26));
            assertThat(sql).containsPattern(
                    "idx_projects_pinned_sort\\s+ON\\s+projects\\s*\\(\\s*pinned\\s+DESC\\s*,\\s*sort_order\\s*,\\s*id\\s*\\)");
        }

        @Test
        void theDocumentIndexIsScopedToAProjectBecauseTheDocumentListAlwaysIs() {
            String sql = executableSql(read(V26));
            assertThat(sql).containsPattern(
                    "idx_documents_project_pinned_sort\\s+ON\\s+documents\\s*\\(\\s*project_id\\s*,\\s*pinned\\s+DESC\\s*,\\s*sort_order\\s*,\\s*id\\s*\\)");
        }
    }

    @Nested
    @DisplayName("The neighbours are untouched")
    class Neighbours {

        @Test
        void v25StillCreatesResourcesAndReservations() {
            String sql = executableSql(read(V25));
            assertThat(sql).contains("CREATE TABLE resources");
            assertThat(sql).contains("CREATE TABLE reservations");
        }

        @Test
        void v1StillCreatesProjectsAndDocuments() {
            String sql = executableSql(read(V1));
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS projects");
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS documents");
        }

        @Test
        void v26NeverAltersATableItDoesNotOwnColumnsFor() {
            for (String statement : statements(executableSql(read(V26)))) {
                if (statement.startsWith("ALTER TABLE")) {
                    assertThat(statement)
                            .as("V26 owns columns of projects and documents only")
                            .matches("(?i)^ALTER TABLE\\s+(projects|documents)\\b.*");
                }
            }
        }
    }
}
