package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SessionTest {
    static Session session(Path work) {
        Engine.Config config = new Engine.Config(Path.of("."), EngineTest.JAVA, EngineTest.CP, null);
        return new Session(config, Path.of(".").toAbsolutePath(), List.of(), List.of(), work);
    }

    public static void main(String[] args) {
        Check c = new Check("SessionTest");

        c.test("a worker start waits for a write in the work directory and the dispose stops the new worker", () -> {
            Path work = Files.createTempDirectory("session-test");
            Session s = session(work);
            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                try {
                    s.workDir.run(() -> { inside.countDown(); release.await(); });
                } catch (Exception e) {
                    // the test fails on the assertions below
                }
            });
            writer.start();
            inside.await();
            Throwable[] failure = new Throwable[1];
            Thread starter = new Thread(() -> {
                try {
                    s.startWorker();
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
            starter.start();
            starter.join(500);
            boolean waited = starter.isAlive();
            release.countDown();
            starter.join(30_000);
            writer.join();
            try {
                yes(waited, "the worker start did not wait for the write that holds the work directory");
                eq(null, failure[0]);
                yes(s.workerAlive(), "no worker is running");
                s.workDir.dispose();
                eq(false, s.workerAlive());
                eq(false, Files.exists(work));
            } finally {
                s.workDir.dispose();
            }
        });

        c.test("a worker start after the dispose is refused and creates no file", () -> {
            Path work = Files.createTempDirectory("session-test");
            Session s = session(work);
            s.workDir.dispose();
            String refusal = null;
            try {
                s.startWorker();
            } catch (Engine.Failure e) {
                refusal = e.getMessage();
            }
            // Without the refusal in WorkDir.run the worker.log redirect fails as well, with another message.
            yes(refusal != null && refusal.contains("the work directory is already removed"),
                "the worker start was not refused by the work directory: " + refusal);
            eq(false, s.workerAlive());
            eq(false, Files.exists(work));
        });

        c.finish();
    }
}
