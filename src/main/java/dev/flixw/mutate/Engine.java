package dev.flixw.mutate;

import java.nio.file.Path;

/** Runs mutation testing on a project: sites, schemata program, one worker JVM, tests per mutant. */
public final class Engine {
    private Engine() { }

    /**
     * @param projectRoot     the project; sources are {@code src/**}{@code /*.flix} (mutated) and {@code test/**}{@code /*.flix}
     * @param java            the java executable the worker runs with
     * @param workerClasspath class path of the worker: the engine classes and the compiler jar
     * @param timeoutMs       timeout per mutant, or null: ten times the baseline run, at least 2000 ms
     */
    public record Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs) {}

    /** The run cannot proceed (the project does not compile, its tests fail, a worker died); exit code 2. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) { super(message); }
    }

    public static Report run(Config config) throws Failure {
        throw new UnsupportedOperationException("not implemented");
    }
}
