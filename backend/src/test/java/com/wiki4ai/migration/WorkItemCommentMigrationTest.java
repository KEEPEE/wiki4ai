package com.wiki4ai.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-148: assertions on the V22 migration file.
 * <p>
 * Tests run on H2 with Flyway disabled — the schema is created by Hibernate from the
 * entities (SPEC rule 5) — so the SQL of V22 is never executed locally, and a mistake
 * in it is found by the pipeline on dev .219, where the postgres profile runs with
 * {@code ddl-auto: validate} and a mismatch stops the backend at startup. That is why
 * these assertions read the file: they are the only place the text of V22 is checked
 * before it is deployed.
 * <p>
 * They are read from the classpath, not from a source path, so they hold wherever the
 * tests run. They do not execute the file: V22 ends in a {@code plpgsql} trigger, which
 * is PostgreSQL-only and would fail on H2 for reasons that say nothing about V22 being
 * correct.
 */
@DisplayName("V22 migration: work_item_comments (WIKI4AI-148)")
class WorkItemCommentMigrationTest {

    private static final String V22 = "db/migration/V22__create_work_item_comments.sql";

    private static String read(String resource) {
        try (InputStream stream = WorkItemCommentMigrationTest.class.getClassLoader()
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
     * The negative assertions have to run on this, not on the raw file: V22 explains
     * itself, and its explanation contains the words it forbids — "There is NO
     * updated_at column", "NO CHECK CONSTRAINT HERE", "a BEFORE DELETE trigger would
     * break". Asserting on the raw text would fail a correct migration for quoting its
     * own reasoning. The statements are what the database executes, and the statements
     * are what these assertions are about.
     */
    private static String executableSql(String sql) {
        String withoutLineComments = sql.replaceAll("(?m)--[^\\n]*", "");
        return withoutLineComments.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    @Test
    @DisplayName("V22 creates work_item_comments with exactly SPEC's columns")
    void createsTheTableOfSpecsDdl() {
        String sql = read(V22);

        assertThat(sql).contains("CREATE TABLE work_item_comments");
        assertThat(sql).contains("id BIGSERIAL PRIMARY KEY");
        assertThat(sql).contains("work_item_id BIGINT NOT NULL REFERENCES work_items(id) ON DELETE CASCADE");
        assertThat(sql).contains("author_actor_id BIGINT REFERENCES actors(id) ON DELETE SET NULL");
        assertThat(sql).contains("kind VARCHAR(16) NOT NULL");
        assertThat(sql).contains("body TEXT NOT NULL");
        assertThat(sql).contains("payload TEXT");
        assertThat(sql).contains("created_at TIMESTAMP NOT NULL DEFAULT now()");
    }

    @Test
    @DisplayName("V22 uses TIMESTAMP, never TIMESTAMPTZ (SPEC rule 11b)")
    void usesTimestampNotTimestamptz() {
        String sql = executableSql(read(V22));

        assertThat(sql)
                .as("TIMESTAMPTZ maps to java.time.Instant, the postgres profile runs with"
                        + " ddl-auto=validate, and the backend on dev .219 would not start")
                .doesNotContainIgnoringCase("TIMESTAMPTZ");
        assertThat(sql).contains("TIMESTAMP NOT NULL DEFAULT now()");
    }

    @Test
    @DisplayName("V22 has no updated_at: a comment is immutable")
    void hasNoUpdatedAtColumn() {
        assertThat(executableSql(read(V22)))
                .as("an immutable row has no later moment to record")
                .doesNotContain("updated_at");
    }

    @Test
    @DisplayName("V22 adds no CHECK constraint: the kind vocabulary is a service rule (SPEC rule 7)")
    void addsNoCheckConstraint() {
        assertThat(executableSql(read(V22)))
                .as("V21 deliberately added no CHECK on work_item_events.event for the same reason:"
                        + " a second copy of the same list, free to drift from the copy the API"
                        + " actually validates")
                .doesNotContainIgnoringCase("CHECK");
    }

    @Test
    @DisplayName("V22 indexes both reads the API performs")
    void createsBothIndexes() {
        String sql = read(V22);

        assertThat(sql).contains("CREATE INDEX idx_wic_item_kind ON work_item_comments (work_item_id, kind)");
        assertThat(sql).contains("CREATE INDEX idx_wic_item_time ON work_item_comments (work_item_id, created_at)");
    }

    @Test
    @DisplayName("V22 makes the table append-only in the schema too: a BEFORE UPDATE trigger")
    void installsTheAppendOnlyTrigger() {
        String sql = read(V22);

        assertThat(sql).contains("CREATE TRIGGER trg_work_item_comments_append_only");
        assertThat(sql).contains("BEFORE UPDATE ON work_item_comments");
        assertThat(sql).contains("FOR EACH ROW");
        assertThat(sql).contains("work_item_comments is append-only");

        assertThat(executableSql(sql))
                .as("no BEFORE DELETE trigger: it would break the ON DELETE CASCADE SPEC's own DDL"
                        + " declares, so removals are refused above the database instead")
                .doesNotContain("BEFORE DELETE");
    }

    @Test
    @DisplayName("V22 is the version SPEC assigns to comments; V23 is left unused")
    void versionNumbersMatchSpec() {
        assertThat(read(V22)).isNotBlank();

        assertThat(WorkItemCommentMigrationTest.class.getClassLoader()
                .getResource("db/migration/V23__create_resources.sql"))
                .as("V23 belongs to the resources and reservations story and stays unused here")
                .isNull();

        // The Jira summary of WIKI4AI-148 says "Migracia V19". V19 is documents
        // attribution and is already applied; reusing its number would break Flyway
        // validation on dev.
        assertThat(WorkItemCommentMigrationTest.class.getClassLoader()
                .getResource("db/migration/V19__add_actor_attribution_to_documents.sql"))
                .isNotNull();
    }

    @Test
    @DisplayName("no migration in the chain up to V22 uses TIMESTAMPTZ")
    void wholeChainIsTimestampOnly() {
        for (String resource : new String[]{
                "db/migration/V20__create_work_items.sql",
                "db/migration/V21__create_work_item_events.sql",
                V22}) {
            assertThat(executableSql(read(resource)))
                    .as("%s must stay on TIMESTAMP so ddl-auto=validate accepts the schema", resource)
                    .doesNotContainIgnoringCase("TIMESTAMPTZ");
        }
    }
}
