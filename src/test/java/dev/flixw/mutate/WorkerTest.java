package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class WorkerTest {
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    static final String CP = System.getProperty("java.class.path");

    /**
     * Starts a worker, writes {@code input} to its stdin, closes stdin and returns the exit code (-1: still
     * running after 30 s, -2: with {@code awaitAnswer}, no answer line within 30 s). With {@code awaitAnswer}
     * stdin closes only after the first line of the answer, so the request is known to be running.
     */
    static int endedByEof(String input, boolean awaitAnswer) throws Exception {
        Process p = new ProcessBuilder(JAVA.toString(), "-cp", CP, Worker.class.getName())
            .redirectOutput(ProcessBuilder.Redirect.PIPE).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            p.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            p.getOutputStream().flush();
            if (awaitAnswer) {
                java.io.BufferedReader answer = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                String[] first = new String[1];
                Thread reader = new Thread(() -> {
                    try {
                        first[0] = answer.readLine();
                    } catch (java.io.IOException e) {
                        // no answer line
                    }
                });
                reader.setDaemon(true);
                reader.start();
                reader.join(30_000);
                if (first[0] == null) return -2;
            }
            p.getOutputStream().close();
            return p.waitFor(30, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } finally {
            p.destroyForcibly().waitFor();
        }
    }

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

        c.test("the worker ends with exit 0 when its stdin closes", () -> eq(0, endedByEof("", false)));

        c.test("the worker ends when its stdin closes after the first answer line of a request", () -> {
            Path calc = SiteFinderTest.FIXTURE;
            Path list = Files.createTempFile("worker-eof", ".list");
            Files.writeString(list, "M\t" + calc.resolve("src/Calc.flix") + "\nF\t" + calc.resolve("test/TestCalc.flix") + "\n");
            int code = endedByEof(Protocol.encode("sites", list.toString()) + "\n", true);
            yes(code == 0, "exit code " + code + " (-1: the worker was still running, -2: no answer)");
        });

        c.finish();
    }
}
