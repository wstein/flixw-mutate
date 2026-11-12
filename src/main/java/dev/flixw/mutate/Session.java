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
import java.util.stream.Stream;

/**
 * One prepared run of the engine: the sites are picked, the schemata program is rendered, compiled and loaded
 * in a worker JVM, and the baseline run has recorded which test evaluates which site. With regions on, the
 * program also carries a marker for every region of the code (function entries, branches, lambda bodies), numbered
 * with the mutants in one table ({@link SitePlan}). It runs mutants and
 * owns the work directory and the worker. A mutant that runs into the timeout ends the worker (nothing else
 * stops a runaway test); the next worker loads the program again.
 */
final class Session implements AutoCloseable {
    private static final List<String> SITES_END = List.of("done", "compile-error", "error");
    private static final List<String> LOAD_END = List.of("loaded", "compile-error", "error");
    private static final List<String> RUN_END = List.of("result", "error");
    /** Upper bound for compile, load and the baseline run. */
    private static final long SETUP_TIMEOUT_MS = 15 * 60 * 1000L;

    private final Engine.Config config;
    private final Path root;
    private final List<Path> src;
    private final List<Path> tests;
    private final Path work;
    final WorkDir workDir;
    private Thread cleanup;
    private volatile WorkerClient worker;
    private Path loadList;
    private Path markerJar;

    private final List<Mutant> mutants = new ArrayList<>();
    private final Map<Integer, String> positions = new TreeMap<>();
    private final Map<Integer, String> files = new TreeMap<>();
    private int skipped;
    private int testCount;
    private long baselineMs;
    private long timeout;
    private Coverage coverage;
    private SiteTable siteTable;
    private SitePlan plan;
    private List<String> testNames = List.of();
    private boolean empty;

    Session(Engine.Config config, Path root, List<Path> src, List<Path> tests, Path work) {
        this.config = config;
        this.root = root;
        this.src = src;
        this.tests = tests;
        this.work = work;
        this.workDir = work == null ? null : new WorkDir(work, this::stopWorker);
    }

    // --- numbers of the prepared run -------------------------------------------------------------------------

    /** True if there is nothing to mutate (no sources, or no sites); no worker ran, or no program was loaded. */
    boolean empty() { return empty; }

    List<Mutant> mutants() { return mutants; }

    String file(Mutant m) { return files.get(m.id()); }

    String position(Mutant m) { return positions.get(m.id()); }

    int skippedSites() { return skipped; }

    int testCount() { return testCount; }

    long baselineMs() { return baselineMs; }

    long timeoutMs() { return timeout; }

    Coverage coverage() { return coverage; }

    // --- opening ---------------------------------------------------------------------------------------------

    static Session open(Engine.Config config) throws Engine.Failure {
        Path root = config.projectRoot().toAbsolutePath().normalize();
        List<Path> src = flixFiles(root.resolve("src"));
        List<Path> tests = flixFiles(root.resolve("test"));
        if (src.isEmpty()) {
            Session s = new Session(config, root, src, tests, null);
            s.empty = true;
            return s;
        }
        for (String entry : config.workerClasspath().split(java.io.File.pathSeparator))
            if (!Files.exists(Path.of(entry))) throw new Engine.Failure("class path entry does not exist: " + entry);
        Path work;
        try {
            work = Files.createTempDirectory("flixw-mutate");
        } catch (IOException e) {
            throw new Engine.Failure("cannot create a work directory: " + e);
        }
        // A SIGTERM skips the finally block. The hook stops the worker, then deletes the directory, and refuses
        // later writes of this thread (WorkDir), so no file appears after the delete.
        Session s = new Session(config, root, src, tests, work);
        s.cleanup = new Thread(s.workDir::dispose, "flixw-mutate-cleanup");
        Runtime.getRuntime().addShutdownHook(s.cleanup);
        try {
            s.prepare();
            return s;
        } catch (IOException e) {
            s.close();
            throw new Engine.Failure("I/O error: " + e);
        } catch (Engine.Failure | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    private void prepare() throws Engine.Failure, IOException {
        // 1. Sites, from the type-checked original program.
        Path sitesList = work.resolve("sites.list");
        List<String> lines = new ArrayList<>();
        for (Path p : src) lines.add("M\t" + p);
        for (Path p : tests) lines.add("F\t" + p);
        workDir.run(() -> Files.write(sitesList, lines, StandardCharsets.UTF_8));
        startWorker();
        List<Protocol.Message> reply = ask(
            config.regions() ? Protocol.encode("sites", sitesList.toString(), "regions") : Protocol.encode("sites", sitesList.toString()),
            SITES_END, SETUP_TIMEOUT_MS);
        Protocol.Message last = reply.get(reply.size() - 1);
        if (!last.verb().equals("done")) throw failed("project does not compile", last);
        Map<String, List<Site>> sites = new TreeMap<>();
        Map<String, List<Region>> regions = new TreeMap<>();
        Map<String, List<Exec>> execs = new TreeMap<>();
        for (Protocol.Message m : reply.subList(0, reply.size() - 1))
            switch (m.verb()) {
                case "site" -> {
                    Site s = Protocol.decodeSite(m);
                    sites.computeIfAbsent(s.file(), k -> new ArrayList<>()).add(s);
                }
                case "region" -> {
                    Region g = Protocol.decodeRegion(m);
                    regions.computeIfAbsent(g.file(), k -> new ArrayList<>()).add(g);
                }
                case "exec" -> {
                    Exec e = Protocol.decodeExec(m);
                    execs.computeIfAbsent(e.file(), k -> new ArrayList<>()).add(e);
                }
                default -> { }
            }
        skipped = Integer.parseInt(last.field(0));
        if (sites.isEmpty()) {
            empty = true;
            return;
        }

        // 2. The numbering of all sites, then the schemata program.
        List<SitePlan.Input> inputs = new ArrayList<>();
        Map<Path, String> texts = new java.util.HashMap<>();
        for (Path p : src) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            texts.put(p, text);
            inputs.add(new SitePlan.Input(root.relativize(p).toString().replace('\\', '/'), text,
                sites.getOrDefault(p.toString(), List.of()), regions.getOrDefault(p.toString(), List.of()),
                execs.getOrDefault(p.toString(), List.of())));
        }
        plan = SitePlan.build(inputs);
        List<String> loadLines = new ArrayList<>();
        Path rendered = work.resolve("rendered");
        for (Path p : src) {
            String text = texts.get(p);
            String rel = root.relativize(p).toString().replace('\\', '/');
            List<Schemata.Item> list = plan.items(rel);
            Path target = p;
            if (!list.isEmpty()) {
                Schemata.Rendered r;
                try {
                    r = Schemata.render(text, list);
                } catch (IllegalArgumentException e) {
                    throw new Engine.Failure("cannot rewrite " + root.relativize(p) + ": " + e.getMessage());
                }
                target = rendered.resolve(root.relativize(p));
                Path out = target;
                workDir.run(() -> {
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, r.text(), StandardCharsets.UTF_8);
                });
                for (Mutant m : r.mutants()) {
                    mutants.add(m);
                    positions.put(m.id(), Schemata.position(text, m.site().whole().start()));
                    files.put(m.id(), rel);
                }
            }
            loadLines.add("F\t" + target);
        }
        siteTable = plan.table();
        Path module = work.resolve("FlixwMutant.flix");
        workDir.run(() -> Files.writeString(module, Schemata.module(), StandardCharsets.UTF_8));
        loadLines.add("F\t" + module);
        for (Path p : tests) loadLines.add("F\t" + p);
        markerJar = work.resolve("marker.jar");
        loadList = work.resolve("load.list");
        workDir.run(() -> Files.write(loadList, loadLines, StandardCharsets.UTF_8));

        // 3. One compile and load; the same switch module answers for the original (switch 0). The baseline run
        // has every switch off and records which test evaluates which site.
        testCount = load();
        if (testCount == 0) throw new Engine.Failure("no tests: the project has no @Test functions that are not skipped");
        long t0 = System.nanoTime();
        coverage = baseline(List.of());
        baselineMs = (System.nanoTime() - t0) / 1_000_000;
        testNames = coverage.tests();
        timeout = config.timeoutMs() != null ? config.timeoutMs() : Engine.defaultTimeout(baselineMs);
    }

    // --- runs ------------------------------------------------------------------------------------------------

    /**
     * Runs the baseline in the order given (name order for an empty list) on the loaded program and returns
     * which switches each test evaluated.
     */
    private Coverage baseline(List<String> order) throws Engine.Failure, IOException {
        String[] fields = order.toArray(new String[0]);
        List<Protocol.Message> reply = ask(Protocol.encode("baseline", fields), RUN_END, SETUP_TIMEOUT_MS);
        Protocol.Message base = reply.get(reply.size() - 1);
        if (!base.verb().equals("result")) throw failed("the baseline run failed", base);
        if (base.field(0).equals("killed"))
            throw new Engine.Failure("tests fail without a mutation: " + base.field(1) + ": " + base.field(2));
        List<Protocol.Message> perTest = reply.subList(0, reply.size() - 1);
        Hits.Builder hits = new Hits.Builder(siteTable, perTest.stream().map(m -> m.field(0)).toList());
        for (Protocol.Message m : perTest)
            for (String id : m.fields().subList(1, m.fields().size())) {
                int n = Integer.parseInt(id);
                hits.reach(m.field(0), n);
                // a part of a line is reached when the region that owns it is
                for (int part : plan.lineParts(n)) hits.reach(m.field(0), part);
            }
        return new Coverage(siteTable, hits.build());
    }

    /**
     * The baseline on a freshly loaded program, with the tests in the given order: the sets of switches must
     * not depend on the order (a site evaluated once at load, or state shared between tests, would show).
     */
    Coverage freshBaseline(List<String> order) throws Engine.Failure, IOException {
        startWorker();
        load();
        return baseline(order);
    }

    /** Runs mutant {@code m}: with test selection only the tests that evaluate its site. */
    Result run(Mutant m) throws Engine.Failure, IOException {
        if (!config.selection()) return run(m, testNames, false);
        List<String> selected = coverage.testsFor(m.id());
        if (selected.isEmpty())
            return new Result(m, file(m), position(m), Outcome.NOT_COVERED, "", 0, 0, testCount);
        return run(m, selected, true);
    }

    /** Runs mutant {@code m} with the named tests only, in the given order. */
    Result run(Mutant m, List<String> selected, boolean selection) throws Engine.Failure, IOException {
        List<String> fields = new ArrayList<>();
        fields.add(String.valueOf(m.id()));
        // Without selection no names are sent: the worker runs all tests in name order.
        if (selection) fields.addAll(selected);
        try {
            Protocol.Message r = askTimed(Protocol.encode("run", fields.toArray(new String[0])), RUN_END, timeout).get(0);
            if (!r.verb().equals("result")) throw failed("mutant " + m.id() + " failed", r);
            if (r.field(0).equals("survived")) {
                int ran = Integer.parseInt(r.field(1));
                return new Result(m, file(m), position(m), Outcome.SURVIVED, "", selected.size(), ran, selection ? testCount : ran);
            }
            int ran = Integer.parseInt(r.field(3));
            int full = selection ? testNames.indexOf(r.field(1)) + 1 : ran;
            return new Result(m, file(m), position(m), Outcome.KILLED, r.field(1) + ": " + r.field(2), selected.size(), ran, full);
        } catch (WorkerClient.Timeout e) {
            // The worker was killed; the next one loads the program again.
            startWorker();
            load();
            return new Result(m, file(m), position(m), Outcome.TIMEOUT, "", selected.size(), selected.size() /* not measured: upper bound */, testCount);
        }
    }

    private int load() throws Engine.Failure, IOException {
        List<Protocol.Message> reply = ask(
            Protocol.encode("load", loadList.toString(), String.valueOf(siteTable.size()), markerJar.toString()),
            LOAD_END, SETUP_TIMEOUT_MS);
        Protocol.Message last = reply.get(reply.size() - 1);
        if (!last.verb().equals("loaded")) throw failed("the schemata program does not compile", last);
        return Integer.parseInt(last.field(0));
    }

    /** True while a worker process runs (for the tests). */
    boolean workerAlive() {
        WorkerClient w = worker;
        return w != null && w.alive();
    }

    /**
     * Starts a worker. It is created inside {@link WorkDir#run}: the process opens worker.log in the work
     * directory, and a shutdown hook that disposes the directory either waits for this and stops the new worker,
     * or runs first and refuses it.
     */
    void startWorker() throws Engine.Failure {
        if (worker != null) worker.close();
        try {
            workDir.run(() -> worker = new WorkerClient(config.java(), config.workerClasspath(), Worker.class.getName(), work.resolve("worker.log")));
        } catch (IOException e) {
            throw new Engine.Failure("cannot start the worker (" + config.java() + "): " + e.getMessage());
        }
    }

    /** A request in which a timeout is a failure of the run. */
    private List<Protocol.Message> ask(String line, List<String> terminal, long timeout) throws Engine.Failure, IOException {
        try {
            return askTimed(line, terminal, timeout);
        } catch (WorkerClient.Timeout e) {
            throw new Engine.Failure("the worker did not answer: " + e.getMessage());
        }
    }

    /** A request whose timeout the caller handles; a dead worker is a failure of the run. */
    private List<Protocol.Message> askTimed(String line, List<String> terminal, long timeout)
            throws Engine.Failure, WorkerClient.Timeout, IOException {
        try {
            return worker.request(line, terminal, timeout);
        } catch (WorkerClient.Died e) {
            throw new Engine.Failure("the worker died: " + e.getMessage() + workerLog());
        }
    }

    private String workerLog() {
        try {
            List<String> log = Files.readAllLines(work.resolve("worker.log"), StandardCharsets.UTF_8);
            return "\n" + String.join("\n", log.subList(Math.max(0, log.size() - 20), log.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private static Engine.Failure failed(String what, Protocol.Message m) {
        return new Engine.Failure(what + ":\n" + String.join("\n", m.fields()));
    }

    // --- files -----------------------------------------------------------------------------------------------

    static List<Path> flixFiles(Path dir) throws Engine.Failure {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> found = Files.walk(dir)) {
            List<Path> out = found.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".flix"))
                .map(Path::toAbsolutePath)
                .sorted(Comparator.comparing((Path p) -> dir.toAbsolutePath().relativize(p).toString().replace('\\', '/'), SiteTable.FILE_ORDER))
                .toList();
            for (Path p : out)
                if (p.toString().contains("\t") || p.toString().contains("\n"))
                    throw new Engine.Failure("a tab or line break in a path is not supported: " + p);
            return out;
        } catch (IOException e) {
            throw new Engine.Failure("cannot read " + dir + ": " + e);
        }
    }

    private void stopWorker() {
        WorkerClient w = worker;
        if (w != null) w.close();
    }

    @Override
    public void close() {
        if (work == null) {
            stopWorker();
            return;
        }
        workDir.dispose();
        try {
            Runtime.getRuntime().removeShutdownHook(cleanup);
        } catch (IllegalStateException e) {
            // already shutting down: the hook does the work
        }
    }
}
