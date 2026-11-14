package dev.flixw.mutate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A JDK-only test runner: each case is a named lambda; the process exits 1 if one fails. */
final class Check {
    private final String suite;
    private final List<String> failures = new ArrayList<>();
    private int passed;

    Check(String suite) { this.suite = suite; }

    interface Body { void run() throws Exception; }

    void test(String name, Body body) {
        try {
            body.run();
            passed++;
        } catch (Throwable t) {
            failures.add(name + ": " + t);
        }
    }

    static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    static void yes(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static <T extends Throwable> void throwsA(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    private static final List<Path> TEMPS = new ArrayList<>();

    /** A new temporary directory; {@link #finish} deletes it (and what it holds) after the last case. */
    static synchronized Path tempDir(String prefix) throws IOException {
        Path p = Files.createTempDirectory(prefix);
        TEMPS.add(p);
        return p;
    }

    /** A new temporary file; {@link #finish} deletes it after the last case. */
    static synchronized Path tempFile(String prefix, String suffix) throws IOException {
        Path p = Files.createTempFile(prefix, suffix);
        TEMPS.add(p);
        return p;
    }

    /** Deletes every registered temporary path; a path that cannot be deleted is reported, the others are still tried. */
    private static synchronized List<String> deleteTemps() {
        List<String> errors = new ArrayList<>();
        for (Path root : TEMPS) {
            List<Path> all;
            try (Stream<Path> walk = Files.walk(root)) {
                all = walk.sorted(Comparator.reverseOrder()).toList();
            } catch (IOException | RuntimeException e) {
                errors.add("cannot list " + root + ": " + e);
                continue;
            }
            for (Path p : all) {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    errors.add("cannot delete " + p + ": " + e);
                }
            }
        }
        TEMPS.clear();
        return errors;
    }

    /** Deletes the temporary paths, prints the summary and exits with 1 on a failure. */
    void finish() {
        for (String e : deleteTemps()) failures.add("temporary files: " + e);
        for (String f : failures) System.err.println("FAIL " + suite + " " + f);
        System.out.println(suite + ": " + passed + " passed, " + failures.size() + " failed");
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
