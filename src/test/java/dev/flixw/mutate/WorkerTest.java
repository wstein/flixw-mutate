package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;

public final class WorkerTest {
    public static void main(String[] args) {
        Check c = new Check("WorkerTest");

        c.test("describe: class and first message line", () ->
            eq("java.lang.IllegalStateException: boom", Worker.describe(new IllegalStateException("boom\nsecond line"))));

        c.test("describe: no message", () -> eq("java.lang.IllegalStateException", Worker.describe(new IllegalStateException())));

        c.test("describe: terminal colour codes are removed", () ->
            eq("java.lang.AssertionError: Assertion failed: expected `6`, but got `2`",
                Worker.describe(new AssertionError("\u001B[38;2;220;105;105mAssertion failed: \u001B[0mexpected \u001B[38;2;1;2;3m`6`\u001B[0m, but got `2`"))));

        c.test("describe: long messages are cut at 160 characters", () -> {
            String d = Worker.describe(new RuntimeException("x".repeat(500)));
            eq(160, d.length());
            eq(true, d.endsWith("..."));
        });

        c.finish();
    }
}
