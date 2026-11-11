package dev.flixw.mutate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

    /**
     * Starts {@code java -cp <classpath> <mainClass>}; the worker's stderr goes to {@code log}.
     */
    WorkerClient(Path java, String classpath, String mainClass, Path log) throws IOException {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Sends one request and returns the messages up to and including the first whose verb is one of
     * {@code terminal}. On a timeout the worker is killed.
     */
    List<Protocol.Message> request(String line, List<String> terminal, long timeoutMs) throws Timeout, Died, IOException {
        throw new UnsupportedOperationException("not implemented");
    }

    boolean alive() { throw new UnsupportedOperationException("not implemented"); }

    @Override
    public void close() { throw new UnsupportedOperationException("not implemented"); }
}
