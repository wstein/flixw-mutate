package dev.flixw.mutate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs mutation testing on a project: picks the sites, renders the schemata program, then, in one worker
 * JVM, compiles and loads it once and runs the tests once per mutant with the switch set. A mutant that
 * runs into the timeout ends the worker (nothing else stops a runaway test); the next worker loads the
 * program again.
 */
public final class Engine {
    private static final List<String> SITES_END = List.of("done", "compile-error", "error");
    private static final List<String> LOAD_END = List.of("loaded", "compile-error", "error");
    private static final List<String> RUN_END = List.of("result", "error");
    /** Upper bound for compile, load and the baseline run. */
    private static final long SETUP_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long MIN_TIMEOUT_MS = 2000;
    private static final long TIMEOUT_FACTOR = 10;

    private Engine() { }

    /**
     * @param projectRoot     the project; sources are {@code src/**}{@code /*.flix} (mutated) and {@code test/**}{@code /*.flix}
     * @param java            the java executable the worker runs with
     * @param workerClasspath class path of the worker: the engine classes and the compiler jar
     * @param timeoutMs       timeout per mutant, or null: ten times the baseline run, at least 2000 ms
     * @param progress        receives one line per finished mutant
     */
    public record Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs, Consumer<String> progress) {
        public Config(Path projectRoot, Path java, String workerClasspath, Long timeoutMs) {
            this(projectRoot, java, workerClasspath, timeoutMs, line -> { });
        }
    }

    /** The run cannot proceed (the project does not compile, its tests fail, a worker died); exit code 2. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String message) { super(message); }
    }

    public static Report run(Config config) throws Failure {
        Path root = config.projectRoot().toAbsolutePath().normalize();
        List<Path> src = flixFiles(root.resolve("src"));
        List<Path> tests = flixFiles(root.resolve("test"));
        if (src.isEmpty()) return new Report(List.of(), 0, 0, 0, 0);
        for (String entry : config.workerClasspath().split(java.io.File.pathSeparator))
            if (!Files.exists(Path.of(entry))) throw new Failure("class path entry does not exist: " + entry);
        Path work;
        try {
            work = Files.createTempDirectory("flixw-mutate");
        } catch (IOException e) {
            throw new Failure("cannot create a work directory: " + e);
        }
        try {
            return new Run(config, root, src, tests, work).execute();
        } catch (IOException e) {
            throw new Failure("I/O error: " + e);
        } finally {
            delete(work);
        }
    }

    /** The timeout per mutant when none is given: ten times the baseline run, at least 2000 ms. */
    static long defaultTimeout(long baselineMs) {
        return Math.max(MIN_TIMEOUT_MS, TIMEOUT_FACTOR * baselineMs);
    }

    private static List<Path> flixFiles(Path dir) throws Failure {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> out = files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".flix"))
                .map(Path::toAbsolutePath).sorted().toList();
            for (Path p : out)
                if (p.toString().contains("\t") || p.toString().contains("\n"))
                    throw new Failure("a tab or line break in a path is not supported: " + p);
            return out;
        } catch (IOException e) {
            throw new Failure("cannot read " + dir + ": " + e);
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // A leftover temporary directory is not worth failing a finished run.
        }
    }

    /** The state of one run. */
    private static final class Run {
        final Config config;
        final Path root;
        final List<Path> src;
        final List<Path> tests;
        final Path work;
        WorkerClient worker;
        Path loadList;

        Run(Config config, Path root, List<Path> src, List<Path> tests, Path work) {
            this.config = config;
            this.root = root;
            this.src = src;
            this.tests = tests;
            this.work = work;
        }

        Report execute() throws Failure, IOException {
            try {
                // 1. Sites, from the type-checked original program.
                Path sitesList = work.resolve("sites.list");
                List<String> lines = new ArrayList<>();
                for (Path p : src) lines.add("M\t" + p);
                for (Path p : tests) lines.add("F\t" + p);
                Files.write(sitesList, lines, StandardCharsets.UTF_8);
                startWorker();
                List<Protocol.Message> reply = ask(Protocol.encode("sites", sitesList.toString()), SITES_END, SETUP_TIMEOUT_MS);
                Protocol.Message last = reply.get(reply.size() - 1);
                if (!last.verb().equals("done")) throw failed("project does not compile", last);
                Map<String, List<Site>> sites = new TreeMap<>();
                for (Protocol.Message m : reply.subList(0, reply.size() - 1))
                    if (m.verb().equals("site")) {
                        Site s = Protocol.decodeSite(m);
                        sites.computeIfAbsent(s.file(), k -> new ArrayList<>()).add(s);
                    }
                int skipped = Integer.parseInt(last.field(0));
                if (sites.isEmpty()) return new Report(List.of(), skipped, 0, 0, 0);

                // 2. The schemata program.
                List<Mutant> mutants = new ArrayList<>();
                Map<Integer, String> positions = new TreeMap<>();
                Map<Integer, String> files = new TreeMap<>();
                List<String> loadLines = new ArrayList<>();
                Path rendered = work.resolve("rendered");
                for (Path p : src) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    List<Site> list = sites.getOrDefault(p.toString(), List.of());
                    Path target = p;
                    if (!list.isEmpty()) {
                        Schemata.Rendered r;
                        try {
                            r = Schemata.render(text, list, mutants.size() + 1);
                        } catch (IllegalArgumentException e) {
                            throw new Failure("cannot rewrite " + root.relativize(p) + ": " + e.getMessage());
                        }
                        target = rendered.resolve(root.relativize(p));
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, r.text(), StandardCharsets.UTF_8);
                        for (Mutant m : r.mutants()) {
                            mutants.add(m);
                            positions.put(m.id(), Schemata.position(text, m.site().whole().start()));
                            files.put(m.id(), root.relativize(p).toString().replace('\\', '/'));
                        }
                    }
                    loadLines.add("F\t" + target);
                }
                Path module = work.resolve("FlixwMutant.flix");
                Files.writeString(module, Schemata.module(), StandardCharsets.UTF_8);
                loadLines.add("F\t" + module);
                for (Path p : tests) loadLines.add("F\t" + p);
                loadList = work.resolve("load.list");
                Files.write(loadList, loadLines, StandardCharsets.UTF_8);

                // 3. One compile and load; the same switch module answers for the original (switch 0).
                int testCount = load();
                if (testCount == 0) throw new Failure("no tests: the project has no @Test functions that are not skipped");
                long t0 = System.nanoTime();
                Protocol.Message base = ask(Protocol.encode("run", "0"), RUN_END, SETUP_TIMEOUT_MS).get(0);
                long baselineMs = (System.nanoTime() - t0) / 1_000_000;
                if (!base.verb().equals("result")) throw failed("the baseline run failed", base);
                if (base.field(0).equals("killed"))
                    throw new Failure("tests fail without a mutation: " + base.field(1) + ": " + base.field(2));
                long timeout = config.timeoutMs() != null ? config.timeoutMs() : defaultTimeout(baselineMs);

                // 4. The mutants.
                List<Result> results = new ArrayList<>();
                for (Mutant m : mutants) {
                    Result r = runMutant(m, files.get(m.id()), positions.get(m.id()), timeout);
                    results.add(r);
                    config.progress().accept("[" + results.size() + "/" + mutants.size() + "] "
                        + r.outcome().name().toLowerCase(java.util.Locale.ROOT) + " " + r.file() + ":" + r.position()
                        + " " + m.from() + " -> " + m.to());
                }
                return new Report(results, skipped, testCount, baselineMs, timeout);
            } finally {
                if (worker != null) worker.close();
            }
        }

        Result runMutant(Mutant m, String file, String position, long timeout) throws Failure, IOException {
            try {
                Protocol.Message r = askTimed(Protocol.encode("run", String.valueOf(m.id())), RUN_END, timeout).get(0);
                if (!r.verb().equals("result")) throw failed("mutant " + m.id() + " failed", r);
                if (r.field(0).equals("survived")) return new Result(m, file, position, Outcome.SURVIVED, "");
                return new Result(m, file, position, Outcome.KILLED, r.field(1) + ": " + r.field(2));
            } catch (WorkerClient.Timeout e) {
                // The worker was killed; the next one loads the program again.
                startWorker();
                load();
                return new Result(m, file, position, Outcome.TIMEOUT, "");
            }
        }

        int load() throws Failure, IOException {
            List<Protocol.Message> reply = ask(Protocol.encode("load", loadList.toString()), LOAD_END, SETUP_TIMEOUT_MS);
            Protocol.Message last = reply.get(reply.size() - 1);
            if (!last.verb().equals("loaded")) throw failed("the schemata program does not compile", last);
            return Integer.parseInt(last.field(0));
        }

        void startWorker() throws Failure {
            if (worker != null) worker.close();
            try {
                worker = new WorkerClient(config.java(), config.workerClasspath(), Worker.class.getName(), work.resolve("worker.log"));
            } catch (IOException e) {
                throw new Failure("cannot start the worker (" + config.java() + "): " + e.getMessage());
            }
        }

        /** A request in which a timeout is a failure of the run. */
        List<Protocol.Message> ask(String line, List<String> terminal, long timeout) throws Failure, IOException {
            try {
                return askTimed(line, terminal, timeout);
            } catch (WorkerClient.Timeout e) {
                throw new Failure("the worker did not answer: " + e.getMessage());
            }
        }

        /** A request whose timeout the caller handles; a dead worker is a failure of the run. */
        List<Protocol.Message> askTimed(String line, List<String> terminal, long timeout)
                throws Failure, WorkerClient.Timeout, IOException {
            try {
                return worker.request(line, terminal, timeout);
            } catch (WorkerClient.Died e) {
                throw new Failure("the worker died: " + e.getMessage() + workerLog());
            }
        }

        String workerLog() {
            try {
                List<String> log = Files.readAllLines(work.resolve("worker.log"), StandardCharsets.UTF_8);
                return "\n" + String.join("\n", log.subList(Math.max(0, log.size() - 20), log.size()));
            } catch (IOException e) {
                return "";
            }
        }

        Failure failed(String what, Protocol.Message m) {
            return new Failure(what + ":\n" + String.join("\n", m.fields()));
        }
    }
}
