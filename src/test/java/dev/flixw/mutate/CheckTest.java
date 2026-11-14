package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The cleanup of {@link Check}: what {@code finish} deletes and how it reports what it cannot. */
public final class CheckTest {
    /** What a finished inner Check printed and returned. */
    record Outcome(int status, String out, String err) { }

    static Outcome complete(Check inner, Check.Deleter deleter) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = inner.complete(new PrintStream(out, true), new PrintStream(err, true), deleter);
        return new Outcome(status, out.toString(), err.toString());
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("CheckTest");

        c.test("finish deletes a registered directory with its contents and a registered file", () -> {
            Path dir = Check.tempDir("check-test");
            Files.createDirectories(dir.resolve("a/b"));
            Files.writeString(dir.resolve("a/b/f"), "x");
            Path file = Check.tempFile("check-test", ".txt");
            Outcome o = complete(new Check("inner"), Files::delete);
            eq(0, o.status());
            eq("inner: 0 passed, 0 failed\n", o.out().replace("\r", ""));
            yes(!Files.exists(dir) && !Files.exists(file), "left behind: " + dir + " " + file);
        });

        c.test("a path that cannot be deleted fails the suite with its path; the other paths are still tried", () -> {
            Path dir = Check.tempDir("check-test");
            Files.writeString(dir.resolve("a"), "x");
            Files.writeString(dir.resolve("b"), "x");
            Path other = Check.tempFile("check-test", ".txt");
            List<Path> tried = new ArrayList<>();
            Outcome o = complete(new Check("inner"), p -> {
                tried.add(p);
                if (p.getFileName().toString().equals("b")) throw new IOException("injected");
                Files.delete(p);
            });
            eq(1, o.status());
            yes(o.err().contains("FAIL inner temporary files: cannot delete " + dir.resolve("b") + ": java.io.IOException: injected"), o.err());
            yes(tried.contains(dir.resolve("a")), "a was not tried after b");
            yes(tried.contains(dir), "the directory was not tried");
            yes(tried.contains(other), "the other registered path was not tried");
            yes(!Files.exists(other), "the other registered path was not deleted");
            yes(o.out().contains("inner: 0 passed, "), o.out());
            // The injected failure left b behind; remove it so this suite leaves nothing.
            Files.delete(dir.resolve("b"));
            Files.delete(dir);
        });

        c.finish();
    }
}
