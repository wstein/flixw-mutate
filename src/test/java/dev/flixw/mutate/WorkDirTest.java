package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class WorkDirTest {
    static Path dir() throws Exception { return Files.createTempDirectory("workdir-test"); }

    public static void main(String[] args) {
        Check c = new Check("WorkDirTest");

        c.test("writes before dispose land in the directory and are removed by it", () -> {
            Path d = dir();
            WorkDir w = new WorkDir(d, () -> { });
            w.run(() -> Files.writeString(d.resolve("a"), "x"));
            yes(Files.exists(d.resolve("a")), "the write did not happen");
            w.dispose();
            eq(false, Files.exists(d));
        });

        c.test("a write after dispose is refused and leaves nothing behind", () -> {
            Path d = dir();
            WorkDir w = new WorkDir(d, () -> { });
            w.dispose();
            throwsA(IOException.class, () -> w.run(() -> Files.writeString(Files.createDirectories(d).resolve("late"), "x")));
            eq(false, Files.exists(d));
        });

        c.test("dispose stops the writer first: the stop action runs while the directory still exists", () -> {
            Path d = dir();
            List<Boolean> seen = new ArrayList<>();
            WorkDir w = new WorkDir(d, () -> seen.add(Files.exists(d)));
            w.dispose();
            eq(List.of(true), seen);
            eq(false, Files.exists(d));
        });

        c.test("dispose twice stops and deletes once", () -> {
            Path d = dir();
            List<Boolean> seen = new ArrayList<>();
            WorkDir w = new WorkDir(d, () -> seen.add(true));
            w.dispose();
            w.dispose();
            eq(1, seen.size());
        });

        c.test("dispose waits for a write that is running", () -> {
            Path d = dir();
            WorkDir w = new WorkDir(d, () -> { });
            java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
            Throwable[] failure = new Throwable[1];
            Thread writer = new Thread(() -> {
                try {
                    w.run(() -> {
                        inside.countDown();
                        Thread.sleep(300);
                        Files.writeString(d.resolve("slow"), "x");
                    });
                } catch (Exception e) {
                    failure[0] = e;
                }
            });
            writer.start();
            inside.await();
            w.dispose();
            writer.join();
            eq(null, failure[0]);
            eq(false, Files.exists(d));
        });

        c.finish();
    }
}
