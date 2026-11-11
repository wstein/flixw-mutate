package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.List;

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

    /** Prints the summary and exits with 1 on a failure. */
    void finish() {
        for (String f : failures) System.err.println("FAIL " + suite + " " + f);
        System.out.println(suite + ": " + passed + " passed, " + failures.size() + " failed");
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}
