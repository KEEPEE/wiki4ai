package com.wiki4ai.migration;

import com.wiki4ai.model.UserProjectOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-200 (PER-1): assertions on the V28 migration file.
 *
 * <p>Same reasoning as {@link PinningSortOrderMigrationTest}: the suite runs on H2 with
 * Flyway disabled and builds its schema from the entities, so the SQL of V28 is never
 * executed locally. Its text is therefore asserted here — it is the only place it is
 * checked before it runs on dev .219, where the postgres profile combines Flyway with
 * {@code ddl-auto: validate} and any disagreement between this file and the entity stops
 * the backend at startup.</p>
 *
 * <h2>Why the version number is asserted at all</h2>
 * The story text asked for "V27", and V27 was already taken by
 * {@code V27__add_embedding_queue.sql} when the story was written. Flyway runs with
 * {@code outOfOrder=false} and the dev database is already at version 27, so a migration
 * numbered 27 or lower does not fail loudly — it stops the dev backend from starting at
 * all. The version guard below is the cheap, permanent answer: it fails the build the
 * moment anybody adds a migration whose number collides with or regresses behind one that
 * is already installed.</p>
 *
 * <h2>What this migration must never become</h2>
 * It is purely additive: one new table, one index, no {@code ALTER}, no {@code DROP}, no
 * {@code UPDATE} of an existing row. A {@code DROP} of {@code projects.pinned} or
 * {@code projects.sort_order} would delete the curated global default this design depends
 * on, and a backfill {@code INSERT} would freeze that default for every existing user
 * instead of letting them keep inheriting it.</p>
 */
@DisplayName("V28: user_project_order (per-user project ordering)")
class UserProjectOrderMigrationTest {

    private static final String V28 = "db/migration/V28__create_user_project_order.sql";
    private static final String MIGRATION_DIR = "db/migration";

    /** {@code V<digits>__<name>.sql} — the shape Flyway itself requires. */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private static String read(String resource) {
        try (InputStream stream = UserProjectOrderMigrationTest.class.getClassLoader()
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

    /**
     * Every {@code V<n>__*.sql} on the classpath, by number.
     *
     * <p>The directory is read as a filesystem path, which is what a surefire run gives
     * (resources are copied to {@code target/classes}). A packaged jar would need a
     * different walk; the caller skips the assertion in that case rather than passing
     * vacuously on an empty list.</p>
     */
    private static List<Long> migrationVersions() {
        URL url = UserProjectOrderMigrationTest.class.getClassLoader().getResource(MIGRATION_DIR);
        assertThat(url).as("%s must be on the classpath", MIGRATION_DIR).isNotNull();
        Path dir;
        try {
            dir = Paths.get(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new AssertionError("cannot walk " + MIGRATION_DIR + " from " + url, e);
        }
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Long> versions = new ArrayList<>();
            for (Path file : files.toList()) {
                Matcher matcher = VERSIONED.matcher(file.getFileName().toString());
                if (matcher.matches()) {
                    versions.add(Long.parseLong(matcher.group(1)));
                }
            }
            return versions;
        } catch (IOException e) {
            throw new AssertionError("could not list " + dir, e);
        }
    }

    // =======================================================================

    @Nested
    @DisplayName("The version number, because outOfOrder=false makes a low number fatal")
    class Version {

        @Test
        void noTwoMigrationsShareAVersion() {
            List<Long> versions = migrationVersions();
            assertThat(versions)
                    .as("the migration directory is readable in this run — an empty list would "
                            + "make every assertion in this class pass vacuously")
                    .isNotEmpty();
            assertThat(versions)
                    .as("a duplicate version means Flyway refuses to migrate, and with "
                            + "outOfOrder=false the backend does not start")
                    .doesNotHaveDuplicates();
        }

        /**
         * Not "V28 is the newest file" — the vault migration comes after this story and
         * will raise the maximum. What must never become true is a migration that sorts
         * BEHIND one the dev database has already installed: V27 is installed there, so
         * anything numbered 27 or lower is out of order, and with {@code outOfOrder=false}
         * Flyway fails the backend at startup rather than skipping the file.
         */
        @Test
        void v28SortsStrictlyAfterTheVersionDevHasAlreadyInstalled() {
            List<Long> versions = migrationVersions();
            assertThat(versions).as("V28 must exist").contains(28L);
            long highest = versions.stream().max(Comparator.naturalOrder()).orElseThrow();
            assertThat(highest)
                    .as("V28 must not sit behind an already-installed migration")
                    .isGreaterThanOrEqualTo(28L);
            assertThat(versions)
                    .as("V27 is the version dev is on, and V28 has to be above it")
                    .contains(27L);
        }

        @Test
        void theEmbeddingQueueMigrationStillHoldsV27() {
            // The reason this story could not use the V27 its text asked for.
            assertThat(read("db/migration/V27__add_embedding_queue.sql")).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Additive, and only additive")
    class Additive {

        @Test
        void v28CreatesExactlyOneTableNamedUserProjectOrder() {
            List<String> creates = matching(read(V28), "^\\s*CREATE TABLE");
            assertThat(creates).hasSize(1);
            assertThat(creates.get(0)).containsPattern("CREATE TABLE\\s+user_project_order");
        }

        @Test
        void v28AltersNothingAndDropsNothing() {
            String sql = executableSql(read(V28));
            assertThat(matching(sql, "^\\s*ALTER TABLE")).isEmpty();
            assertThat(sql).doesNotContain("DROP");
            assertThat(sql).doesNotContain("TRUNCATE");
        }

        /**
         * The global columns of V26 are the curated default order every user without a row
         * inherits. A backfill would copy that order into a row per (user, project) and
         * freeze it, so a later change of the default would stop reaching existing users.
         */
        @Test
        void v28BackfillsNothing() {
            String sql = executableSql(read(V28));
            assertThat(matching(sql, "^\\s*INSERT")).isEmpty();
            assertThat(matching(sql, "^\\s*UPDATE")).isEmpty();
        }

        @Test
        void v26StillOwnsTheGlobalColumnsItIsTheDefaultFor() {
            String sql = executableSql(read("db/migration/V26__add_pinning_and_sort_order.sql"));
            assertThat(sql).containsPattern("ALTER TABLE\\s+projects\\s+ADD COLUMN\\s+pinned");
            assertThat(sql).containsPattern("ALTER TABLE\\s+projects\\s+ADD COLUMN\\s+sort_order");
        }
    }

    @Nested
    @DisplayName("The columns and the two constraints")
    class Columns {

        @Test
        void bothForeignKeysCascadeOnDelete() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern(
                    "user_id\\s+BIGINT\\s+NOT\\s+NULL\\s+REFERENCES\\s+users\\s*\\(\\s*id\\s*\\)\\s+ON\\s+DELETE\\s+CASCADE");
            assertThat(sql).containsPattern(
                    "project_id\\s+BIGINT\\s+NOT\\s+NULL\\s+REFERENCES\\s+projects\\s*\\(\\s*id\\s*\\)\\s+ON\\s+DELETE\\s+CASCADE");
        }

        /**
         * One row per (viewer, project) is the whole feature: a viewer has exactly one
         * position and one pin state for a project. Without it the same project could be
         * listed twice at two positions, and every write would have to be an upsert that
         * nothing guarantees.
         */
        @Test
        void aViewerHasAtMostOneRowPerProject() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern(
                    "CONSTRAINT\\s+uk_user_project_order\\s+UNIQUE\\s*\\(\\s*user_id\\s*,\\s*project_id\\s*\\)");
        }

        @Test
        void pinnedAndSortOrderAreNotNullWithTheSameDefaultsAsTheGlobalColumns() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern("pinned\\s+BOOLEAN\\s+NOT\\s+NULL\\s+DEFAULT\\s+FALSE");
            assertThat(sql).containsPattern("sort_order\\s+INTEGER\\s+NOT\\s+NULL\\s+DEFAULT\\s+0");
        }

        /**
         * SPEC rule 11b: TIMESTAMP without a time zone, mapping to LocalDateTime. The
         * postgres profile runs with {@code ddl-auto: validate}, so a TIMESTAMPTZ here and
         * a LocalDateTime on the entity stops the backend at startup.
         */
        @Test
        void timeColumnsAreTimestampWithoutTimeZone() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern("created_at\\s+TIMESTAMP\\s+NOT\\s+NULL");
            assertThat(sql).containsPattern("updated_at\\s+TIMESTAMP\\s+NOT\\s+NULL");
            assertThat(sql).doesNotContain("TIMESTAMPTZ");
        }

        @Test
        void theIdIsABigSerialLikeEveryOtherTableHere() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern("id\\s+BIGSERIAL\\s+PRIMARY\\s+KEY");
        }
    }

    @Nested
    @DisplayName("The index the per-viewer list needs")
    class Indexes {

        @Test
        void v28CreatesExactlyOneIndex() {
            assertThat(matching(read(V28), "^\\s*CREATE INDEX")).hasSize(1);
        }

        /**
         * The list is served as "this viewer's rows, pinned first, then their manual
         * order", so user_id has to lead and pinned DESC has to precede sort_order — the
         * same shape as idx_projects_pinned_sort from V26, scoped to one viewer.
         */
        @Test
        void theIndexIsTheOrderTheProjectListIsServedIn() {
            String sql = executableSql(read(V28));
            assertThat(sql).containsPattern(
                    "idx_user_project_order_viewer\\s+ON\\s+user_project_order\\s*\\(\\s*user_id\\s*,\\s*pinned\\s+DESC\\s*,\\s*sort_order\\s*\\)");
        }
    }

    /**
     * The dev postgres profile runs Flyway and then Hibernate {@code validate}. A column
     * the entity maps under a different name, or a {@code LocalDateTime} facing a
     * {@code TIMESTAMPTZ}, does not fail a request — it stops the backend at startup. The
     * suite cannot see that because it builds its H2 schema from the entities, so the two
     * definitions are compared here instead.
     */
    @Nested
    @DisplayName("The entity and the DDL say the same thing")
    class EntityAgreement {

        private static final Map<String, String> EXPECTED_COLUMNS = Map.of(
                "userId", "user_id",
                "projectId", "project_id",
                "pinned", "pinned",
                "sortOrder", "sort_order",
                "createdAt", "created_at",
                "updatedAt", "updated_at");

        @Test
        void theEntityMapsTheTableV28Creates() {
            Table table = UserProjectOrder.class.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(table.name()).isEqualTo("user_project_order");
        }

        @Test
        void everyColumnTheEntityMapsExistsInTheDdlAndIsNotNull() {
            String sql = executableSql(read(V28));
            for (Map.Entry<String, String> entry : EXPECTED_COLUMNS.entrySet()) {
                Field field = ReflectionUtils.findField(UserProjectOrder.class, entry.getKey());
                assertThat(field)
                        .as("the entity must have a %s field", entry.getKey())
                        .isNotNull();
                Column column = field.getAnnotation(Column.class);
                assertThat(column)
                        .as("%s must be an explicit @Column", entry.getKey())
                        .isNotNull();
                assertThat(column.name())
                        .as("%s must map the column V28 names it", entry.getKey())
                        .isEqualTo(entry.getValue());
                assertThat(column.nullable())
                        .as("every column of this table is NOT NULL in V28")
                        .isFalse();
                assertThat(sql).containsPattern(
                        "\\b" + entry.getValue() + "\\s+(BIGINT|BOOLEAN|INTEGER|TIMESTAMP)\\s+NOT\\s+NULL");
            }
        }

        /**
         * A wrapper type would let the entity represent a NULL the schema forbids, and an
         * {@code Instant} would meet a {@code TIMESTAMP} only by accident of the driver.
         */
        @Test
        void theFieldTypesMatchTheColumnTypesOneForOne() {
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "userId").getType())
                    .isEqualTo(Long.class);
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "projectId").getType())
                    .isEqualTo(Long.class);
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "pinned").getType())
                    .isEqualTo(boolean.class);
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "sortOrder").getType())
                    .isEqualTo(int.class);
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "createdAt").getType())
                    .isEqualTo(LocalDateTime.class);
            assertThat(ReflectionUtils.findField(UserProjectOrder.class, "updatedAt").getType())
                    .isEqualTo(LocalDateTime.class);
        }

        /**
         * The constraint is declared in both places on purpose: V28 is what production
         * enforces, the entity is what the H2 schema the suite builds enforces. If the two
         * column lists drift, the suite stops proving the property it is named for.
         */
        @Test
        void theEntityDeclaresTheSameUniquenessAsV28() {
            UniqueConstraint[] constraints =
                    UserProjectOrder.class.getAnnotation(Table.class).uniqueConstraints();
            assertThat(constraints).hasSize(1);
            assertThat(constraints[0].name()).isEqualTo("uk_user_project_order");
            assertThat(constraints[0].columnNames()).containsExactly("user_id", "project_id");
        }
    }
}
