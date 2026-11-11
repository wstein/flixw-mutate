package dev.flixw.mutate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The work directory of a {@link Session}. The shutdown hook disposes it while the main thread may still be
 * preparing the run, and the worker may still be writing (the marker jar). {@link #dispose} first stops the
 * writer, then deletes; writes of the main thread go through {@link #run}, which is refused once the
 * directory is disposed, so no file appears after the delete.
 */
final class WorkDir {
    interface Write { void run() throws Exception; }

    private final Path dir;
    private final Runnable stopWriters;
    private boolean disposed;

    WorkDir(Path dir, Runnable stopWriters) {
        this.dir = dir;
        this.stopWriters = stopWriters;
    }

    Path path() { return dir; }

    /** Runs a write into the directory; refused with an {@link IOException} after {@link #dispose}. */
    synchronized void run(Write write) throws IOException {
        if (disposed) throw new IOException("the work directory is already removed");
        try {
            write.run();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Stops the writers, then deletes the directory; the second call does nothing. Waits for a write that is running. */
    synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        stopWriters.run();
        try (Stream<Path> found = Files.walk(dir)) {
            found.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A leftover temporary directory is not worth failing a finished run.
        }
    }
}
