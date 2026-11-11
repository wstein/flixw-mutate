package dev.flixw.mutate;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A worker JVM and its line protocol, with a timeout per request. */
final class WorkerClient implements AutoCloseable {
    /** The worker did not answer in time and was killed. */
    static final class Timeout extends Exception {
        private static final long serialVersionUID = 1L;

        Timeout(String message) { super(message); }
    }

    /** The worker ended before it answered. */
    static final class Died extends Exception {
        private static final long serialVersionUID = 1L;

        Died(String message) { super(message); }
    }

    /** Marks the end of the worker's output in the queue. */
    private static final String EOF = new String("<eof>");

    private final Process process;
    private final BufferedWriter toWorker;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    /**
     * Starts {@code java -cp <classpath> <mainClass>}; the worker's stderr goes to {@code log}.
     */
    WorkerClient(Path java, String classpath, String mainClass, Path log) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-cp", classpath, mainClass);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));
        process = pb.start();
        toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try {
                for (String line; (line = fromWorker.readLine()) != null; ) lines.add(line);
            } catch (IOException e) {
                // The stream closed; the end of the output is reported below.
            } finally {
                lines.add(EOF);
            }
        }, "worker-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Sends one request and returns the messages up to and including the first whose verb is one of
     * {@code terminal}. On a timeout the worker is killed.
     */
    List<Protocol.Message> request(String line, List<String> terminal, long timeoutMs) throws Timeout, Died, IOException {
        try {
            toWorker.write(line);
            toWorker.write('\n');
            toWorker.flush();
        } catch (IOException e) {
            throw new Died("worker is not running: " + e.getMessage());
        }
        // toNanos saturates; the time left is computed by difference, which nanoTime allows.
        long start = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        List<Protocol.Message> out = new ArrayList<>();
        while (true) {
            String next;
            try {
                next = lines.poll(Math.max(0, budget - (System.nanoTime() - start)), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                kill();
                throw new IOException("interrupted", e);
            }
            if (next == null) {
                kill();
                throw new Timeout("no answer within " + timeoutMs + " ms");
            }
            if (next == EOF) {
                lines.add(EOF);
                throw new Died("worker exited with code " + exitCode());
            }
            Protocol.Message m = Protocol.decode(next);
            out.add(m);
            if (terminal.contains(m.verb())) return out;
        }
    }

    private String exitCode() {
        try {
            if (process.waitFor(5, TimeUnit.SECONDS)) return String.valueOf(process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "unknown";
    }

    private void kill() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean alive() { return process.isAlive(); }

    @Override
    public void close() { kill(); }
}
