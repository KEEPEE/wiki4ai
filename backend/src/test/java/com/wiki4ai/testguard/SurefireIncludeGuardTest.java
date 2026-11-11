package com.wiki4ai.testguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIKI4AI-202 — the guard that keeps {@code *IT} classes from silently stopping to run.
 *
 * <h2>Why this test exists</h2>
 * Surefire's default includes are {@code **&#47;Test*.java}, {@code **&#47;*Test.java},
 * {@code **&#47;*Tests.java} and {@code **&#47;*TestCase.java}. {@code *IT.java} is not one
 * of them. A class named {@code *IT} is therefore compiled, lands in
 * {@code target/test-classes}, is never executed, and the build is green — the tool reports
 * success without having looked. Five such classes sat in this suite for the whole life of
 * the repository; two of them could not even start their Spring context, and nobody noticed
 * because nothing ever tried.
 *
 * <h2>Why a guard and not just the {@code <includes>} block</h2>
 * The include lives in {@code pom.xml}, where the next person who decides "integration
 * tests belong to failsafe" can remove it with the best intentions — and the suite would go
 * green again while quietly losing 95 tests. {@code .gitlab-ci.yml} runs {@code mvn -q test},
 * not {@code mvn verify}, so failsafe would not catch them either. This test turns that
 * regression into a red build instead of a smaller number nobody compares.
 *
 * <h2>Why static analysis</h2>
 * Same reasoning as {@link SharedTableStateGuardTest}: reading the sources and the pom is
 * independent of ordering, of a database and of a Spring context. It starts none of them.
 *
 * <h2>Why an empty scan is not accepted as success</h2>
 * A guard whose input list is empty passes vacuously — which is exactly how this bug hid.
 * {@link #guardActuallySeesTheItClasses()} therefore asserts the scan finds the {@code *IT}
 * classes by name, so a wrong root path fails loudly instead of silently.
 */
class SurefireIncludeGuardTest {

    private static final Path TEST_SOURCE_ROOT = locate("src/test/java");
    private static final Path POM = locateFile("pom.xml");

    /** Surefire's own default patterns. {@code <includes>} REPLACES them, it does not add. */
    private static final List<String> SUREFIRE_DEFAULTS = List.of(
            "<include>**/Test*.java</include>",
            "<include>**/*Test.java</include>",
            "<include>**/*Tests.java</include>",
            "<include>**/*TestCase.java</include>");

    private static final String IT_INCLUDE = "<include>**/*IT.java</include>";

    @Test
    @DisplayName("every *IT class in the suite is matched by a surefire include")
    void itClassesAreIncludedBySurefire() throws IOException {
        List<String> itClasses = itClasses();
        if (itClasses.isEmpty()) {
            // Nothing to guard today. Not a failure — but see guardActuallySeesTheItClasses(),
            // which fails if this ever becomes vacuous by accident.
            return;
        }

        String pom = Files.readString(POM);
        if (!pom.contains(IT_INCLUDE)) {
            throw new AssertionError("""
                    WIKI4AI-202 regression: %d *IT classes exist in this suite but
                    backend/pom.xml does not include **/*IT.java in maven-surefire-plugin.
                    They are compiled and never executed, and the build stays green:

                    %s

                    Surefire's default includes are Test*, *Test, *Tests and *TestCase —
                    *IT is not among them. Add the include (and keep the four defaults next
                    to it, because <includes> replaces the defaults). Do NOT add
                    maven-failsafe-plugin instead: .gitlab-ci.yml runs `mvn -q test`, not
                    `mvn verify`, so failsafe-bound *IT classes would still never run in CI.
                    """.formatted(itClasses.size(), String.join("\n", itClasses)));
        }
    }

    @Test
    @DisplayName("the include block still lists all four surefire default patterns")
    void includeBlockKeepsTheDefaults() throws IOException {
        String pom = Files.readString(POM);
        if (!pom.contains(IT_INCLUDE)) {
            // Covered by itClassesAreIncludedBySurefire(); nothing to check underneath it.
            return;
        }

        List<String> missing = SUREFIRE_DEFAULTS.stream().filter(p -> !pom.contains(p)).toList();
        if (!missing.isEmpty()) {
            throw new AssertionError("""
                    WIKI4AI-202: <includes> REPLACES surefire's default patterns, it does not
                    extend them. The *IT include is present but these defaults are not:
                    %s
                    Every test that relied on one of them has just stopped running, and the
                    build is green.
                    """.formatted(missing));
        }
    }

    @Test
    @DisplayName("the guard is not vacuous: it really finds the *IT classes by name")
    void guardActuallySeesTheItClasses() throws IOException {
        List<String> itClasses = itClasses();

        // The five classes WIKI4AI-202 woke up. If the scan loses them it is reading the
        // wrong directory, and the first test would pass on an empty list — the exact
        // failure mode that let this bug hide for the life of the repository.
        assertThat(itClasses)
                .as("*IT classes discovered under %s", TEST_SOURCE_ROOT)
                .contains(
                        "com/wiki4ai/config/InitialAdminBootstrapIT.java",
                        "com/wiki4ai/controller/AdminControllerIT.java",
                        "com/wiki4ai/controller/AuthControllerIT.java",
                        "com/wiki4ai/controller/AuthControllerUpdateProfileIT.java",
                        "com/wiki4ai/controller/PermissionControllerIT.java");

        // And the pom it reads is the real module pom, not an empty string.
        assertThat(Files.readString(POM)).contains("maven-surefire-plugin");
    }

    /** Every {@code *IT.java} under the test sources, as paths relative to the test root. */
    private static List<String> itClasses() throws IOException {
        try (Stream<Path> walk = Files.walk(TEST_SOURCE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("IT.java"))
                    .map(p -> TEST_SOURCE_ROOT.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static Path locate(String relative) {
        Path found = locateOrNull(relative, Files::isDirectory);
        if (found == null) {
            throw new IllegalStateException("cannot locate " + relative
                    + " - the guard test must run from the backend module directory");
        }
        return found;
    }

    private static Path locateFile(String relative) {
        Path found = locateOrNull(relative, Files::isRegularFile);
        if (found == null) {
            throw new IllegalStateException("cannot locate " + relative
                    + " - the guard test must run from the backend module directory");
        }
        return found;
    }

    private static Path locateOrNull(String relative, java.util.function.Predicate<Path> exists) {
        Path cwd = Paths.get("").toAbsolutePath();
        if (exists.test(cwd.resolve(relative))) {
            return cwd.resolve(relative);
        }
        try {
            Path here = Paths.get(SurefireIncludeGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            for (Path p = here; p != null; p = p.getParent()) {
                if (exists.test(p.resolve(relative))) {
                    return p.resolve(relative);
                }
            }
        } catch (Exception whereAmI) {
            // fall through to the caller's error
        }
        return null;
    }
}
