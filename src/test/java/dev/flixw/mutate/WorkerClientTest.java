package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class WorkerClientTest {
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    static final String CP = System.getProperty("java.class.path");

    static WorkerClient start(Path log) throws Exception {
        return new WorkerClient(JAVA, CP, "dev.flixw.mutate.FakeWorker", log);
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("WorkerClientTest");
        Path dir = Files.createTempDirectory("worker-client-test");

        c.test("a request returns the messages up to the terminal one", () -> {
            try (WorkerClient w = start(dir.resolve("a.log"))) {
                List<Protocol.Message> r = w.request(Protocol.encode("echo", "x\ty"), List.of("done"), 20_000);
                eq(List.of("echo", "done"), r.stream().map(Protocol.Message::verb).toList());
                eq("x\ty", r.get(0).field(0));
            }
        });

        c.test("several messages arrive in order", () -> {
            try (WorkerClient w = start(dir.resolve("b.log"))) {
                List<Protocol.Message> r = w.request(Protocol.encode("multi"), List.of("done"), 20_000);
                eq(List.of("part", "part", "done"), r.stream().map(Protocol.Message::verb).toList());
                eq("2", r.get(1).field(0));
            }
        });

        c.test("the worker is reused for the next request", () -> {
            try (WorkerClient w = start(dir.resolve("c.log"))) {
                w.request(Protocol.encode("echo", "1"), List.of("done"), 20_000);
                eq("2", w.request(Protocol.encode("echo", "2"), List.of("done"), 20_000).get(0).field(0));
            }
        });

        c.test("a request that hangs times out and the worker is killed", () -> {
            try (WorkerClient w = start(dir.resolve("d.log"))) {
                w.request(Protocol.encode("echo", "warm"), List.of("done"), 20_000);
                long t0 = System.nanoTime();
                throwsA(WorkerClient.Timeout.class, () -> w.request(Protocol.encode("hang"), List.of("done"), 300));
                yes((System.nanoTime() - t0) / 1_000_000 < 10_000, "took too long");
                eq(false, w.alive());
                throwsA(WorkerClient.Died.class, () -> w.request(Protocol.encode("echo", "x"), List.of("done"), 1000));
            }
        });

        c.test("a worker that exits is reported with its exit code", () -> {
            try (WorkerClient w = start(dir.resolve("e.log"))) {
                try {
                    w.request(Protocol.encode("die"), List.of("done"), 20_000);
                    throw new AssertionError("no exception");
                } catch (WorkerClient.Died e) {
                    yes(e.getMessage().contains("3"), e.getMessage());
                }
            }
        });

        c.test("the worker's stderr is written to the log", () -> {
            Path log = dir.resolve("f.log");
            try (WorkerClient w = start(log)) {
                w.request(Protocol.encode("echo", "x"), List.of("done"), 20_000);
            }
            yes(Files.readString(log).contains("fake worker started"), "log: " + Files.readString(log));
        });

        c.test("close ends the worker", () -> {
            WorkerClient w = start(dir.resolve("g.log"));
            w.request(Protocol.encode("echo", "x"), List.of("done"), 20_000);
            w.close();
            eq(false, w.alive());
        });

        c.finish();
    }
}
