package com.wiki4ai.testguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static scanner behind {@link SharedTableStateGuardTest} (WIKI4AI-169).
 *
 * <p>It never starts a Spring context, never opens a database and never runs a test. It
 * reads {@code *.java} source text. That is deliberate: the defect it guards against is a
 * property of <em>what a test asserts</em>, not of what the suite happens to do in one
 * particular Surefire ordering — so the check itself must not depend on ordering, on the
 * database, or on a Spring context.
 *
 * <h2>What counts as an order-dependent assertion</h2>
 * An assertion that fixes the <b>total number of rows of a whole table</b>:
 * <pre>
 *   assertThat(userRepository.count()).isZero();
 *   assertThat(userRepository.count()).isOne();
 *   assertThat(projectRepository.count()).as("only one").isEqualTo(1);
 * </pre>
 * Deliberately NOT flagged:
 * <ul>
 *   <li>{@code count()).isEqualTo(projectsBefore)} — a comparison against a value the same
 *       test measured. Relative, so it survives any ordering.</li>
 *   <li>{@code when(userRepository.count()).thenReturn(0L)} — a Mockito stub, not a claim
 *       about a database.</li>
 *   <li>{@code findBySlug("x")).isEmpty()} — a claim about one key, not about the table's
 *       total. Different, weaker failure mode; out of this guard's scope.</li>
 * </ul>
 *
 * <h2>What "the test established that state itself" means</h2>
 * This is the decision this guard encodes, and the reason earlier fixes kept leaking. A
 * fixed total is legal only when the enclosing test class makes the table's <b>starting
 * state known to that class alone</b>. Three accepted ways:
 * <ol>
 *   <li><b>OWN DATABASE</b> — the class overrides {@code spring.datasource.url} to a named
 *       in-memory H2 other than {@value #SHARED_DB}. Nobody else can have written into it,
 *       so "this table is empty" is a fact about that class, not about the suite.</li>
 *   <li><b>ISOLATED SLICE</b> — the class is a {@code @DataJpaTest} / {@code @JdbcTest}
 *       slice: Spring Boot hands it a private embedded database and rolls the test method
 *       back.</li>
 *   <li><b>SELF-EMPTIED</b> — a {@code @BeforeEach} / {@code @BeforeAll} of that class (or
 *       of a class it is nested inside) clears that repository: {@code deleteAll()},
 *       {@code deleteAllById(..)}, {@code deleteAllInBatch(..)}, a
 *       {@code findAll()}/{@code delete(..)} loop, or a {@code DELETE FROM <table>} /
 *       {@code TRUNCATE TABLE <table>} statement. A lifecycle method that delegates to a
 *       helper such as {@code cleanState()} counts, because the helper is resolved.</li>
 * </ol>
 *
 * <p><b>Creating rows yourself is deliberately not one of the three.</b> Saving two
 * projects and asserting {@code count() == 2} still fails when somebody ran before you.
 * That is exactly the WIKI4AI-169 bug, so "the test wrote the rows" is not accepted as an
 * excuse for a fixed total.
 *
 * <p>A class that clears a whole table is accepted here, because clearing is what makes its
 * own assertions order-independent. Whether it <em>should</em> clear the whole table or only
 * its own rows is the separate cleanup convention in SPEC rule 5b and is not what this
 * guard fails a build for.
 */
public final class SharedTableStateScanner {

    /** The single named in-memory H2 every {@code @SpringBootTest} context shares. */
    public static final String SHARED_DB = "testdb";

    public record Violation(Path file, int line, String testClass, String repository,
                            String assertion, long assertedTotal, String reason) {

        /** One line, class name first — what a failing build has to print. */
        public String describe() {
            return String.format("%s:%d  %s  ->  %s   [%s]",
                    file, line, testClass, assertion, reason);
        }
    }

    public record Result(List<Path> filesScanned, int fixedTotalAssertions,
                         List<Violation> violations) {
        public int accepted() {
            return fixedTotalAssertions - violations.size();
        }
    }

    // ── patterns ──────────────────────────────────────────────────────────────

    /**
     * {@code <x>Repository.count()) [.anything(..)]* .isZero|.isOne|.isEqualTo(<arg>)}
     * The middle group exists because AssertJ lets {@code .as("...")} sit between
     * {@code assertThat(..)} and the terminal assertion, and several tests in this suite
     * use it — a pattern that only matched the direct form silently missed them.
     */
    private static final Pattern FIXED_TOTAL = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*Repository)\\s*\\.\\s*count\\s*\\(\\s*\\)\\s*\\)"
                    + "(?:\\s*\\.\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*\\))*"
                    + "\\s*\\.\\s*(isZero|isOne|isEqualTo)\\s*\\(([^)]*)\\)");

    private static final Pattern CLASS_DECL = Pattern.compile(
            "^(?:(?:public|private|protected|static|final|abstract|sealed|strictfp)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern NAMED_H2 = Pattern.compile("jdbc:h2:mem:([A-Za-z0-9_\\-]+)");

    private static final Pattern LIFECYCLE_ANNOTATION =
            Pattern.compile("^@(BeforeEach|BeforeAll|AfterEach|AfterAll)\\b");

    private static final Set<String> NOT_A_METHOD_NAME = Set.of(
            "if", "for", "while", "switch", "try", "catch", "finally", "do", "else",
            "synchronized", "return", "new", "assert", "assertThat", "assertThatThrownBy",
            "findAll", "deleteAll", "deleteAllById", "count", "save", "flush");

    // ── entry points ──────────────────────────────────────────────────────────

    /** Scans every {@code *.java} under {@code root}. */
    public static Result scan(Path root) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        List<Violation> violations = new ArrayList<>();
        int assertions = 0;
        for (Path f : files) {
            assertions += scanFile(f, violations);
        }
        violations.sort(Comparator.comparing(Violation::describe));
        return new Result(files, assertions, violations);
    }

    /** Scans exactly one file. Used by the fixture self-tests. */
    public static Result scanOne(Path file) throws IOException {
        List<Violation> violations = new ArrayList<>();
        int assertions = scanFile(file, violations);
        violations.sort(Comparator.comparing(Violation::describe));
        return new Result(List.of(file), assertions, violations);
    }

    public record AnnotationHit(Path file, int line, String text) {
        public String describe() {
            return file + ":" + line + "  " + text;
        }
    }

    /**
     * Finds real uses of an annotation across {@code *.java} under {@code root}.
     *
     * <p>Searches the comment-blanked <em>and</em> literal-blanked view, so prose that
     * explains why the annotation is NOT used — this suite has twenty such sentences —
     * does not count as using it.
     */
    public static List<AnnotationHit> findAnnotation(Path root, String simpleName) throws IOException {
        Pattern p = Pattern.compile("^\\s*@" + Pattern.quote(simpleName) + "\\b");
        List<AnnotationHit> hits = new ArrayList<>();
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(q -> q.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        for (Path f : files) {
            String code = blank(blank(Files.readString(f, StandardCharsets.UTF_8), true), false);
            List<String> ls = lines(code);
            for (int i = 0; i < ls.size(); i++) {
                Matcher m = p.matcher(ls.get(i));
                if (m.find()) {
                    hits.add(new AnnotationHit(f, i + 1, ls.get(i).trim()));
                }
            }
        }
        return hits;
    }

    private static int scanFile(Path file, List<Violation> out) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);

        // Two views of the same file, both preserving the original line numbering:
        //   code    — comments AND literals blanked, so the assertion pattern can only
        //             match real code. A javadoc quoting the forbidden pattern (this very
        //             class does) must not manufacture a violation.
        //   visible — comments blanked, literals KEPT, because both a private datasource
        //             ("jdbc:h2:mem:mydb") and a native clear
        //             ("DELETE FROM work_item_comments") live inside a string literal.
        String code = blank(blank(raw, true), false);
        String visible = blank(raw, true);

        List<String> codeLines = lines(code);
        List<String> visibleLines = lines(visible);
        int[] lineStart = lineStartOffsets(code);

        List<Scope> classes = parseClasses(codeLines, visibleLines);
        if (classes.isEmpty()) {
            return 0;
        }

        int count = 0;
        Matcher m = FIXED_TOTAL.matcher(code);
        while (m.find()) {
            count++;
            String repository = m.group(1);
            String terminal = m.group(2);
            long total = totalOf(terminal, m.group(3));
            int line = lineOf(lineStart, m.start()) + 1;

            Scope owner = innermostClassContaining(classes, line);
            if (owner == null) {
                continue;
            }
            if (isJustified(chainOf(classes, owner), repository)) {
                continue;
            }
            out.add(new Violation(file, line, owner.name, repository,
                    m.group().replaceAll("\\s+", " ").trim(), total,
                    "no private datasource, no isolated slice and no @BeforeEach/@BeforeAll "
                            + "clears " + repository + " - the number depends on which test "
                            + "class Surefire ran first"));
        }
        return count;
    }

    /** {@code isZero}/{@code isOne} take no argument; {@code 0L}/{@code 1L} are literals. */
    private static long totalOf(String terminal, String argument) {
        if (terminal.equals("isZero")) {
            return 0L;
        }
        if (terminal.equals("isOne")) {
            return 1L;
        }
        String a = argument.trim();
        if (a.endsWith("L") || a.endsWith("l")) {
            a = a.substring(0, a.length() - 1);
        }
        try {
            return Long.parseLong(a);
        } catch (NumberFormatException notALiteral) {
            return -1L; // compared to a variable the test measured -> relative
        }
    }

    // ── justification ─────────────────────────────────────────────────────────

    private static boolean isJustified(List<Scope> chain, String repository) {
        String table = tableOf(repository);
        for (Scope c : chain) {
            if (c.ownDatabase || c.isolatedSlice || c.clears(repository, table)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code workItemCommentRepository} -> {@code work_item_comments}. Only used to
     * recognise {@code jdbcTemplate.execute("DELETE FROM work_item_comments")} as a clear:
     * the two append-only repositories refuse {@code deleteAll()} by design, so tests
     * empty those tables with native SQL.
     */
    static String tableOf(String repository) {
        String head = repository.replaceAll("(?i)Repository$", "");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < head.length(); i++) {
            char ch = head.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ── source views ──────────────────────────────────────────────────────────

    /**
     * Blanks out comments, and optionally string/char literals, while preserving every
     * newline so that line N of the result is line N of the input.
     */
    static String blank(String src, boolean commentsOnly) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i < n) {
                    if (src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/') {
                        i += 2;
                        break;
                    }
                    if (src.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                int literalStart = i;
                boolean textBlock = quote == '"' && src.startsWith("\"\"\"", i);
                if (textBlock) {
                    i += 3;
                    while (i < n && !src.startsWith("\"\"\"", i)) {
                        i++;
                    }
                    i = Math.min(n, i + 3);
                } else {
                    i++;
                    while (i < n && src.charAt(i) != quote) {
                        if (src.charAt(i) == '\\') {
                            i++;
                        }
                        i++;
                    }
                    i = Math.min(n, i + 1);
                }
                if (commentsOnly) {
                    out.append(src, literalStart, i);
                } else {
                    out.append(quote).append(quote);
                    for (int k = literalStart; k < i; k++) {
                        if (src.charAt(k) == '\n') {
                            out.append('\n');
                        }
                    }
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    // ── structure ─────────────────────────────────────────────────────────────

    private static final class Scope {
        final String name;
        final int openLine; // 1-based, the declaration line
        final int depthAtOpen;
        int closeLine;      // 1-based, inclusive
        final List<String> annotations = new ArrayList<>();
        /** Visible-view lines of this class, ownLines.get(0) == line openLine. */
        final List<String> ownLines = new ArrayList<>();
        final List<String> lifecycleBodies = new ArrayList<>();
        boolean ownDatabase;
        boolean isolatedSlice;

        Scope(String name, int openLine, int depthAtOpen) {
            this.name = name;
            this.openLine = openLine;
            this.depthAtOpen = depthAtOpen;
            this.closeLine = openLine;
        }

        boolean clears(String repository, String table) {
            for (String body : lifecycleBodies) {
                if (clearsIn(body, repository, table)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean clearsIn(String body, String repository, String table) {
        String repo = Pattern.quote(repository);
        for (String method : new String[]{"deleteAll", "deleteAllById", "deleteAllInBatch"}) {
            if (Pattern.compile("\\b" + repo + "\\s*\\.\\s*" + method + "\\s*\\(").matcher(body).find()) {
                return true;
            }
        }
        // for (User u : userRepository.findAll()) { userRepository.delete(u); }
        boolean findsAll = Pattern.compile("\\b" + repo + "\\s*\\.\\s*findAll\\s*\\(").matcher(body).find();
        boolean deletes = Pattern.compile("\\b" + repo + "\\s*\\.\\s*delete\\s*\\(").matcher(body).find();
        if (findsAll && deletes) {
            return true;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String candidate : new String[]{table, table + "s"}) {
            String t = Pattern.quote(candidate);
            if (Pattern.compile("delete\\s+from\\s+" + t + "\\b").matcher(lower).find()
                    || Pattern.compile("truncate\\s+table\\s+" + t + "\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Scope> parseClasses(List<String> codeLines, List<String> visibleLines) {
        List<Scope> all = new ArrayList<>();
        List<Scope> stack = new ArrayList<>();
        List<String> pendingAnnotations = new ArrayList<>();
        int depth = 0;

        for (int i = 0; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            String trimmed = line.trim();
            boolean annotationLine = trimmed.startsWith("@");

            if (annotationLine) {
                pendingAnnotations.add(trimmed);
            } else {
                Matcher cm = CLASS_DECL.matcher(trimmed);
                if (cm.find()) {
                    Scope s = new Scope(cm.group(1), i + 1, depth);
                    s.annotations.addAll(pendingAnnotations);
                    pendingAnnotations.clear();
                    all.add(s);
                    stack.add(s);
                } else if (looksLikeMethodDeclaration(trimmed)) {
                    pendingAnnotations.clear();
                }
                for (int k = 0; k < line.length(); k++) {
                    char ch = line.charAt(k);
                    if (ch == '{') {
                        depth++;
                    } else if (ch == '}') {
                        depth--;
                        while (!stack.isEmpty() && depth == stack.get(stack.size() - 1).depthAtOpen) {
                            stack.get(stack.size() - 1).closeLine = i + 1;
                            stack.remove(stack.size() - 1);
                        }
                    }
                }
            }

            for (Scope s : stack) {
                s.ownLines.add(visibleLines.get(i));
            }
        }

        // A class's "own" lines exclude the bodies of its nested classes: a nested class's
        // @BeforeEach is not the outer class's cleanup.
        for (Scope outer : all) {
            for (Scope inner : all) {
                if (inner == outer
                        || inner.openLine <= outer.openLine
                        || inner.closeLine > outer.closeLine) {
                    continue;
                }
                for (int ln = inner.openLine; ln <= inner.closeLine; ln++) {
                    int idx = ln - outer.openLine;
                    if (idx >= 0 && idx < outer.ownLines.size()) {
                        outer.ownLines.set(idx, "");
                    }
                }
            }
        }

        for (Scope s : all) {
            classify(s);
        }
        return all;
    }

    private static void classify(Scope s) {
        String own = String.join("\n", s.ownLines);
        String annotations = String.join(" ", s.annotations);

        if (annotations.contains("DataJpaTest") || annotations.contains("JdbcTest")
                || annotations.contains("DataJdbcTest") || annotations.contains("JpaTest")) {
            // @AutoConfigureTestDatabase(replace = Replace.NONE) opts the private embedded
            // database back out — then the slice shares testdb again.
            s.isolatedSlice = !annotations.contains("AutoConfigureTestDatabase")
                    || !annotations.contains("NONE");
        }

        boolean declaresUrl = own.contains("spring.datasource.url")
                || annotations.contains("spring.datasource.url");
        if (declaresUrl) {
            Matcher m = NAMED_H2.matcher(own);
            while (m.find()) {
                if (!SHARED_DB.equals(m.group(1))) {
                    s.ownDatabase = true;
                    break;
                }
            }
        }

        for (int i = 0; i < s.ownLines.size(); i++) {
            if (!LIFECYCLE_ANNOTATION.matcher(s.ownLines.get(i).trim()).find()) {
                continue;
            }
            int decl = i;
            while (decl < s.ownLines.size() && !looksLikeMethodDeclaration(s.ownLines.get(decl))) {
                decl++;
            }
            if (decl >= s.ownLines.size()) {
                continue;
            }
            String body = blockAt(s.ownLines, decl);
            if (body == null) {
                continue;
            }
            s.lifecycleBodies.add(body);
            // A lifecycle method that delegates to a helper (cleanState()) is only as good
            // as that helper, so the helper is resolved and folded in.
            expandHelperCalls(s, body, s.lifecycleBodies, new HashSet<>());
        }
    }

    private static boolean looksLikeMethodDeclaration(String line) {
        String t = line.trim();
        return t.contains("(") && t.endsWith("{") && !t.startsWith("@");
    }

    /** The brace-matched block that starts on {@code startLineIndex}. */
    private static String blockAt(List<String> lines, int startLineIndex) {
        int depth = 0;
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = startLineIndex; i < lines.size(); i++) {
            String line = lines.get(i);
            sb.append(line).append('\n');
            for (int k = 0; k < line.length(); k++) {
                char ch = line.charAt(k);
                if (ch == '{') {
                    depth++;
                    started = true;
                } else if (ch == '}') {
                    depth--;
                }
            }
            if (started && depth == 0) {
                return sb.toString();
            }
        }
        return null;
    }

    private static void expandHelperCalls(Scope s, String body, List<String> sink, Set<String> seen) {
        Matcher m = Pattern.compile("\\b([a-z][A-Za-z0-9_]*)\\s*\\(\\s*\\)").matcher(body);
        while (m.find()) {
            String name = m.group(1);
            if (NOT_A_METHOD_NAME.contains(name) || !seen.add(name)) {
                continue;
            }
            String helper = methodBody(s, name);
            if (helper != null) {
                sink.add(helper);
                expandHelperCalls(s, helper, sink, seen);
            }
        }
    }

    private static String methodBody(Scope s, String name) {
        Pattern decl = Pattern.compile(
                "^\\s*(?:(?:public|private|protected|static|final)\\s+)*[\\w<>?,.\\[\\]\\s]+?\\b"
                        + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws [^{]*)?\\{");
        for (int i = 0; i < s.ownLines.size(); i++) {
            if (decl.matcher(s.ownLines.get(i)).find()) {
                return blockAt(s.ownLines, i);
            }
        }
        return null;
    }

    private static Scope innermostClassContaining(List<Scope> classes, int line) {
        Scope best = null;
        for (Scope s : classes) {
            if (s.openLine <= line && line <= s.closeLine
                    && (best == null || s.openLine > best.openLine)) {
                best = s;
            }
        }
        return best;
    }

    /** Innermost first, then every enclosing class: a {@code @Nested} class inherits its
     * enclosing class's {@code @BeforeEach}. */
    private static List<Scope> chainOf(List<Scope> classes, Scope start) {
        List<Scope> chain = new ArrayList<>();
        Scope cur = start;
        while (cur != null) {
            chain.add(cur);
            Scope parent = null;
            for (Scope s : classes) {
                if (s != cur && s.openLine < cur.openLine && cur.closeLine <= s.closeLine
                        && (parent == null || s.openLine > parent.openLine)) {
                    parent = s;
                }
            }
            cur = parent;
        }
        return chain;
    }

    // ── line bookkeeping ──────────────────────────────────────────────────────

    private static List<String> lines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    private static int[] lineStartOffsets(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] out = new int[starts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = starts.get(i);
        }
        return out;
    }

    private static int lineOf(int[] lineStart, int offset) {
        int lo = 0;
        int hi = lineStart.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStart[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private SharedTableStateScanner() {
    }
}
