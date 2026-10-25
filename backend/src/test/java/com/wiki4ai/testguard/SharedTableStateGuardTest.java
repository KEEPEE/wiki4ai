package com.wiki4ai.testguard;

import com.wiki4ai.testguard.SharedTableStateScanner.AnnotationHit;
import com.wiki4ai.testguard.SharedTableStateScanner.Result;
import com.wiki4ai.testguard.SharedTableStateScanner.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * WIKI4AI-169 — the guard that is supposed to stop this bug coming back.
 *
 * <h2>Why this test exists</h2>
 * Every Spring test context in this suite points at the same named in-memory H2,
 * {@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}, with {@code create-drop}. State therefore
 * survives from one cached context to the next inside one JVM. A test that asserts
 * "the users table is empty" passes or fails depending on which class Surefire happened
 * to run first, and Surefire's default order is {@code filesystem} — a property of the
 * file system, not of the code. That is why the suite was green locally and red in CI,
 * and green in CI and red locally.
 *
 * <h2>Why it is static analysis and not a test run</h2>
 * A guard that runs tests can only ever prove that <em>one</em> ordering worked. This one
 * reads source text, so it is independent of ordering, of the database and of a Spring
 * context — it starts none of them. It is the only shape of check that can actually hold
 * the invariant "the suite does not assume an order".
 *
 * <h2>What it forbids</h2>
 * A test asserting a fixed total of a whole table — {@code count()).isZero()},
 * {@code count()).isOne()}, {@code count()).isEqualTo(<literal>)} — in a class that did
 * not establish that total. See {@link SharedTableStateScanner} for the exact, documented
 * decision about what "established" means (own database, isolated slice, or a
 * {@code @BeforeEach}/{@code @BeforeAll} that clears that repository). Writing rows yourself
 * is deliberately <b>not</b> accepted: saving two rows and asserting two still fails when
 * somebody ran before you.
 *
 * <h2>Why an empty result is a proof and not an accident</h2>
 * {@link #detectorFindsTheForbiddenPattern()} runs the same detector over
 * {@code src/test/resources/testguard-fixtures/}, which contains four deliberate
 * violations, and asserts it finds exactly those four, named by file, line and class.
 * {@link #detectorAcceptsTheLegalPatterns()} runs it over five deliberate compliant
 * assertions and asserts it saw all five and rejected none. So the real scan returning
 * zero violations means "the detector works and the suite is clean", not "the detector
 * looked at nothing".
 *
 * <p>Fixtures live under {@code src/test/resources}, not {@code src/test/java}: they are
 * never compiled and never run, and they are not part of the real scan — otherwise the
 * guard would fail on its own fixtures.
 */
class SharedTableStateGuardTest {

    private static final Path TEST_SOURCE_ROOT = locate("src/test/java");
    private static final Path FIXTURE_ROOT = locate("src/test/resources/testguard-fixtures");

    @Test
    @DisplayName("no test asserts a table total that its own class did not establish")
    void suiteHasNoOrderDependentTotalAssertion() throws IOException {
        Result result = SharedTableStateScanner.scan(TEST_SOURCE_ROOT);
        if (!result.violations().isEmpty()) {
            fail("WIKI4AI-169 regression: " + result.violations().size()
                    + " of " + result.fixedTotalAssertions() + " fixed-total assertions in "
                    + result.filesScanned().size() + " test sources assert a number the class"
                    + " did not establish. The suite depends on class order again:\n\n"
                    + describe(result.violations())
                    + "\n\nFix it the SPEC rule 5b way: give the class its own datasource, or"
                    + " clear that repository in @BeforeEach AND @AfterEach - cleaning only"
                    + " what the test itself created. Do NOT add @DirtiesContext: it drops the"
                    + " schema the other cached contexts are still using (59 errors last time)."
                    + " Do NOT delete or disable the assertion.");
        }
    }

    @Test
    @DisplayName("the guard really reads the real suite (an empty list is not an accident)")
    void guardActuallySeesTheRealSuite() throws IOException {
        Result result = SharedTableStateScanner.scan(TEST_SOURCE_ROOT);

        // 99 test sources in this suite; a scan that found far fewer is scanning the wrong
        // directory, which is the failure mode that makes a guard silently useless.
        assertThatSizeAtLeast(result.filesScanned().size(), 95, "test sources scanned");

        // The suite genuinely does contain fixed-total assertions - 26 of them today. They
        // are accepted because their classes establish them. "accepted > 0" is what proves
        // the detector is not simply matching nothing, and not simply flagging everything.
        assertThatSizeAtLeast(result.fixedTotalAssertions(), 20, "fixed-total assertions found");
        assertThatSizeAtLeast(result.accepted(), 20, "fixed-total assertions accepted as justified");

        if (!result.violations().isEmpty()) {
            fail("expected zero violations on the real suite, got:\n" + describe(result.violations()));
        }
    }

    @Test
    @DisplayName("detector is not vacuous: it finds every deliberate violation in the fixtures")
    void detectorFindsTheForbiddenPattern() throws IOException {
        Result result = SharedTableStateScanner.scan(FIXTURE_ROOT);

        // Four deliberate violations: three in OrderDependentFixture (one of them inside a
        // @Nested class, which must inherit the outer class's - absent - cleanup) and one in
        // WritesButDoesNotEmptyFixture, which documents that writing rows is not an excuse.
        if (result.violations().size() != 4) {
            fail("expected exactly 4 fixture violations, got " + result.violations().size()
                    + ":\n" + describe(result.violations()));
        }

        List<String> classes = result.violations().stream()
                .map(Violation::testClass)
                .sorted()
                .toList();
        if (!classes.equals(List.of("NestedClaimsZeroWithoutCleaning", "OrderDependentFixture",
                "OrderDependentFixture", "WritesButDoesNotEmptyFixture"))) {
            fail("detector named the wrong classes: " + classes);
        }

        List<String> repositories = result.violations().stream()
                .map(Violation::repository)
                .sorted()
                .toList();
        if (!repositories.equals(List.of("projectRepository", "projectRepository",
                "userRepository", "userRepository"))) {
            fail("detector named the wrong repositories: " + repositories);
        }

        // It names file AND line, which is what a failing build has to print.
        String report = describe(result.violations());
        for (String expected : List.of("OrderDependentFixture.java", "WritesButDoesNotEmptyFixture.java")) {
            if (!report.contains(expected)) {
                fail("violation report does not name " + expected + ":\n" + report);
            }
        }
        for (Violation v : result.violations()) {
            if (v.line() <= 0) {
                fail("violation has no line number: " + v.describe());
            }
            if (v.assertedTotal() < 0) {
                fail("violation has no asserted total: " + v.describe());
            }
        }
    }

    @Test
    @DisplayName("detector accepts the three legal ways of establishing a total")
    void detectorAcceptsTheLegalPatterns() throws IOException {
        // CompliantFixtures.java holds three classes and five fixed-total assertions:
        //   own named datasource (not testdb), @DataJpaTest slice, and a @BeforeEach that
        //   delegates to cleanState() - including a native DELETE FROM for the append-only
        //   table whose repository refuses deleteAll().
        Result compliant = SharedTableStateScanner.scanOne(FIXTURE_ROOT.resolve("CompliantFixtures.java"));

        if (compliant.fixedTotalAssertions() != 5) {
            fail("expected the detector to SEE 5 compliant assertions, it saw "
                    + compliant.fixedTotalAssertions() + " - a guard that misses the pattern"
                    + " in the compliant cases is not a guard");
        }
        if (!compliant.violations().isEmpty()) {
            fail("detector flagged compliant tests:\n" + describe(compliant.violations()));
        }

        // And the forbidden fixture in the same directory is flagged, so the difference
        // between the two files is the justification, not a scanning accident.
        Result forbidden = SharedTableStateScanner.scanOne(FIXTURE_ROOT.resolve("OrderDependentFixture.java"));
        if (forbidden.violations().size() != 3 || forbidden.fixedTotalAssertions() != 3) {
            fail("expected 3 seen / 3 flagged in OrderDependentFixture, got seen="
                    + forbidden.fixedTotalAssertions() + " flagged=" + forbidden.violations().size()
                    + ":\n" + describe(forbidden.violations()));
        }
    }

    @Test
    @DisplayName("@DirtiesContext stays banned: it drops the schema the other contexts share")
    void noTestClassUsesDirtiesContext() throws IOException {
        List<AnnotationHit> hits = SharedTableStateScanner.findAnnotation(TEST_SOURCE_ROOT, "DirtiesContext");
        if (!hits.isEmpty()) {
            fail("WIKI4AI-169: @DirtiesContext is not a fix, it is the bug. Every context in"
                    + " this suite shares jdbc:h2:mem:testdb; closing one makes Hibernate's"
                    + " create-drop drop the schema the rest are still using - that produced"
                    + " 59 'Table \"API_TOKENS\" not found' errors. Found:\n"
                    + hits.stream().map(AnnotationHit::describe).collect(Collectors.joining("\n")));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertThatSizeAtLeast(int actual, int floor, String what) {
        if (actual < floor) {
            fail("expected at least " + floor + " " + what + ", got " + actual
                    + " - the scanner is probably looking at the wrong path");
        }
    }

    private static String describe(List<Violation> violations) {
        return violations.stream().map(Violation::describe).collect(Collectors.joining("\n"));
    }

    /**
     * Resolves a path inside the Maven module. Surefire runs with the module directory as
     * the working directory, but the code-source location is used as a fallback so the
     * guard also works from an IDE that starts elsewhere.
     */
    private static Path locate(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve(relative).resolve("com/wiki4ai/Wiki4AiApplicationTests.java"))
                || Files.isDirectory(cwd.resolve(relative))) {
            return cwd.resolve(relative);
        }
        try {
            Path here = Paths.get(SharedTableStateGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            for (Path p = here; p != null; p = p.getParent()) {
                if (Files.isDirectory(p.resolve(relative))) {
                    return p.resolve(relative);
                }
            }
        } catch (Exception whereAmI) {
            // fall through to the error below
        }
        throw new IllegalStateException("cannot locate " + relative
                + " - the guard test must run from the backend module directory");
    }
}
