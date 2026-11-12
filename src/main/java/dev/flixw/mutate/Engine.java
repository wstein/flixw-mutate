package dev.flixw.mutate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs mutation testing on a project: picks the sites, renders the schemata program, then, in one worker
 * JVM, compiles and loads it once, records in a baseline run which test evaluates which site, and runs
 * each mutant with the tests that evaluate its site (all tests without selection); a mutant no test
 * evaluates is {@link Outcome#NOT_COVERED}. See {@link Session}.
 */
public final class Engine {
    private static final long MIN_TIMEOUT_MS = 2000;
    private static final long TIMEOUT_FACTOR = 10;

    private Engine() { }

    /**
     * @param projectRoot     the project; sources are {@code src/**}{@code /*.flix} (mutated) and {@code test/**}{@code /*.flix}
     * @param java            the java executable the worker runs with
     * @param workerClasspath class path of the worker: the engine classes and the compiler jar
     * @param timeoutMs       timeout per mutant, or null: ten times the baseline run, at least 2000 ms
     * @param progress        receives one line per finished mutant
     * @param selection       run only the tests that evaluate the mutant's site (false: all tests for every mutant)
     * @param regions         also mark the regions of the code (function entries, branches, lambda bodies) and the lines they own
     *                        in the same program; they are rows of the site table and change no mutant, no outcome and no report line
     * @param datalog         also record which test solves which Datalog rule and fact (rows of the site table, level reached);
     *                        off: the run is as before
     */
    public record Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs, Consumer<String> progress,
                         boolean selection, boolean regions, boolean datalog) {
        public Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs) {
            this(projectRoot, java, workerClasspath, timeoutMs, line -> { });
        }

        public Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs, Consumer<String> progress) {
            this(projectRoot, java, workerClasspath, timeoutMs, progress, true, false, false);
        }

        public Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs, Consumer<String> progress,
                      boolean selection) {
            this(projectRoot, java, workerClasspath, timeoutMs, progress, selection, false, false);
        }

        /** The same run with every test for every mutant. */
        public Config withoutSelection() {
            return new Config(projectRoot, java, workerClasspath, timeoutMs, progress, false, regions, datalog);
        }

        /** The same run with the regions of the code marked in the program as well. */
        public Config withRegions() {
            return new Config(projectRoot, java, workerClasspath, timeoutMs, progress, selection, true, datalog);
        }

        /** The same run with the Datalog rules and facts of the sources recorded per test as well. */
        public Config withDatalog() {
            return new Config(projectRoot, java, workerClasspath, timeoutMs, progress, selection, regions, true);
        }
    }

    /** The run cannot proceed (the project does not compile, its tests fail, a worker died); exit code 2. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) { super(message); }
    }

    public static Report run(Config config) throws Failure {
        try (Session session = Session.open(config)) {
            if (session.empty()) return new Report(List.of(), session.skippedSites(), 0, 0, 0);
            List<Result> results = new ArrayList<>();
            for (Mutant m : session.mutants()) {
                Result r = session.run(m);
                results.add(r);
                config.progress().accept("[" + results.size() + "/" + session.mutants().size() + "] "
                    + r.outcome().label() + " " + r.file() + ":" + r.position() + " " + m.from() + " -> " + m.to());
            }
            return new Report(results, session.skippedSites(), session.testCount(), session.baselineMs(),
                session.timeoutMs(), session.coverage(), config.selection());
        } catch (IOException e) {
            throw new Failure("I/O error: " + e);
        }
    }

    /** The timeout per mutant when none is given: ten times the baseline run, at least 2000 ms. */
    static long defaultTimeout(long baselineMs) {
        return Math.max(MIN_TIMEOUT_MS, TIMEOUT_FACTOR * baselineMs);
    }
}
