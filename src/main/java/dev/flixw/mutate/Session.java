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
    /** Orders the selected tests of a mutant; built from the baseline durations. */
    private TestOrder order = new TestOrder(Map.of());
    /** Duration per test of the baseline run of {@link #prepare}, in nanoseconds. */
    private Map<String, Long> testNanos = Map.of();
    private boolean empty;
    private boolean warnedNoSolver;
    /** The fingerprints of the models of each test's solves in the baseline run (Datalog mutants). */
    private final Map<String, List<String>> baselineModels = new TreeMap<>();

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
        List<String> request = new ArrayList<>(List.of(sitesList.toString()));
        if (config.regions()) request.add("regions");
        if (config.datalog()) request.add("datalog");
        List<Protocol.Message> reply = ask(Protocol.encode("sites", request.toArray(new String[0])), SITES_END, SETUP_TIMEOUT_MS);
        Protocol.Message last = reply.get(reply.size() - 1);
        if (!last.verb().equals("done")) throw failed("project does not compile", last);
        Map<String, List<Site>> sites = new TreeMap<>();
        Map<String, List<Region>> regions = new TreeMap<>();
        Map<String, List<Exec>> execs = new TreeMap<>();
        Map<String, List<DatalogSite>> datalogSites = new TreeMap<>();
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
                case "dlsite" -> {
                    DatalogSite d = Protocol.decodeDatalogSite(m);
                    datalogSites.computeIfAbsent(d.file(), k -> new ArrayList<>()).add(d);
                }
                default -> { }
            }
        skipped = Integer.parseInt(last.field(0));
        if (sites.isEmpty() && datalogSites.isEmpty()) {
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
                execs.getOrDefault(p.toString(), List.of()), datalogSites.getOrDefault(p.toString(), List.of())));
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
        if (config.datalogMutants()) addDatalogMutants(texts);
        Path module = work.resolve("FlixwMutant.flix");
        workDir.run(() -> Files.writeString(module, Schemata.module(), StandardCharsets.UTF_8));
        loadLines.add("F\t" + module);
        if (config.datalog()) {
            Path hook = work.resolve(DatalogHook.FILE);
            workDir.run(() -> Files.writeString(hook, DatalogHook.source(), StandardCharsets.UTF_8));
            loadLines.add("F\t" + hook);
        }
        for (Path p : tests) loadLines.add("F\t" + p);
        markerJar = work.resolve("marker.jar");
        loadList = work.resolve("load.list");
        workDir.run(() -> Files.write(loadList, loadLines, StandardCharsets.UTF_8));

        // 3. One compile and load; the same switch module answers for the original (switch 0). The baseline run
        // has every switch off and records which test evaluates which site.
        testCount = load();
        if (testCount == 0) throw new Engine.Failure("no tests: the project has no @Test functions that are not skipped");
        long t0 = System.nanoTime();
        Baseline base = baseline(List.of());
        coverage = base.coverage();
        testNanos = base.nanos();
        baselineMs = (System.nanoTime() - t0) / 1_000_000;
        testNames = coverage.tests();
        order = new TestOrder(testNanos);
        timeout = config.timeoutMs() != null ? config.timeoutMs() : Engine.defaultTimeout(baselineMs);
    }

    /**
     * One mutant for each Datalog rule (dropped) and for each body atom of each rule (dropped), one for each fact (dropped).
     * The ids follow those of the site table: a Datalog mutant is no switch, so the numbers only have to differ.
     */
    private void addDatalogMutants(Map<Path, String> texts) {
        int id = siteTable.size();
        for (Map.Entry<Integer, DatalogSite> e : plan.datalogSites().entrySet()) {
            DatalogSite d = e.getValue();
            // the rule of a query (`query p select ...`) is plumbing of the query, not a rule of the program
            if (d.kind() == Point.Kind.RULE && d.key().startsWith("$Result<-")) continue;
            Path path = Path.of(d.file());
            String rel = root.relativize(path).toString().replace('\\', '/');
            List<DatalogMutation> ops = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            if (d.kind() == Point.Kind.FACT) {
                ops.add(new DatalogMutation(e.getKey(), DatalogMutation.FACT, d.key().replace("\"", ""), 0));
                labels.add("(removed)");
            } else {
                ops.add(new DatalogMutation(e.getKey(), DatalogMutation.RULE, d.key(), 0));
                labels.add("(removed)");
                List<String> atoms = DatalogSite.bodyAtoms(d.key());
                for (int i = 0; i < atoms.size(); i++) {
                    ops.add(new DatalogMutation(e.getKey(), DatalogMutation.ATOM, d.key(), i));
                    labels.add("without body atom " + (i + 1) + " " + atoms.get(i));
                }
            }
            String text = texts.get(path);
            for (int i = 0; i < ops.size(); i++) {
                Mutant m = new Mutant(++id, null, (d.kind() == Point.Kind.FACT ? "fact " : "rule ") + d.key(), labels.get(i), ops.get(i));
                mutants.add(m);
                positions.put(m.id(), text == null ? d.line() + ":1" : Schemata.position(text, d.span().start()));
                files.put(m.id(), rel);
            }
        }
    }

    // --- runs ------------------------------------------------------------------------------------------------

    /**
     * Runs the baseline in the order given (name order for an empty list) on the loaded program and returns
     * which switches each test evaluated.
     */
    private Baseline baseline(List<String> order) throws Engine.Failure, IOException {
        String[] fields = order.toArray(new String[0]);
        List<Protocol.Message> reply = ask(Protocol.encode("baseline", fields), RUN_END, SETUP_TIMEOUT_MS);
        Protocol.Message base = reply.get(reply.size() - 1);
        if (!base.verb().equals("result")) throw failed("the baseline run failed", base);
        if (base.field(0).equals("killed"))
            throw new Engine.Failure("tests fail without a mutation: " + base.field(1) + ": " + base.field(2));
        List<Protocol.Message> perTest = reply.subList(0, reply.size() - 1).stream().filter(m -> m.verb().equals("covered")).toList();
        Map<String, Long> nanos = new java.util.HashMap<>();
        for (Protocol.Message m : reply)
            if (m.verb().equals("timed")) nanos.put(m.field(0), parseNanos(m));
        Hits.Builder hits = new Hits.Builder(siteTable, perTest.stream().map(m -> m.field(0)).toList());
        for (Protocol.Message m : perTest)
            for (String id : m.fields().subList(1, m.fields().size())) {
                int n = Integer.parseInt(id);
                hits.reach(m.field(0), n);
                // a part of a line is reached when the region that owns it is
                for (int part : plan.lineParts(n)) hits.reach(m.field(0), part);
            }
        baselineModels.clear();
        for (Protocol.Message m : reply)
            if (m.verb().equals("dlmodels")) baselineModels.put(m.field(0), m.fields().subList(1, m.fields().size()));
        if (config.datalog()) {
            // what the solves of each test contained, matched to the rule and fact rows of the table
            Map<String, List<String>> rules = new TreeMap<>();
            Map<String, List<String>> facts = new TreeMap<>();
            for (Protocol.Message m : reply)
                if (m.verb().equals("dlhit"))
                    (m.field(1).equals("R") ? rules : facts).computeIfAbsent(m.field(0), k -> new ArrayList<>()).add(m.field(2));
            for (Protocol.Message m : perTest) {
                String test = m.field(0);
                for (int id : DatalogMap.reached(plan.datalogSites(), test, rules.getOrDefault(test, List.of()), facts.getOrDefault(test, List.of())))
                    hits.reach(test, id);
            }
        }
        return new Baseline(new Coverage(siteTable, hits.build()), nanos);
    }

    /** The result of a baseline run: which test evaluates which site, and the duration of each test. */
    private record Baseline(Coverage coverage, Map<String, Long> nanos) { }

    /** The duration in nanoseconds of a {@code timed <test> <ns>} line; a malformed line fails the run. */
    static long parseNanos(Protocol.Message m) throws Engine.Failure {
        try {
            return Long.parseLong(m.field(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new Engine.Failure("malformed timed line from the worker: " + String.join("\t", m.fields()));
        }
    }

    /** Duration per test of the baseline run, in nanoseconds. */
    Map<String, Long> testNanos() { return testNanos; }

    /**
     * The baseline on a freshly loaded program, with the tests in the given order: the sets of switches must
     * not depend on the order (a site evaluated once at load, or state shared between tests, would show).
     */
    Coverage freshBaseline(List<String> order) throws Engine.Failure, IOException {
        startWorker();
        load();
        return baseline(order).coverage();
    }

    /**
     * Runs mutant {@code m}: with test selection only the tests that evaluate its site, fast and often killing
     * tests first unless {@link Engine.Config#order()} is off ({@link TestOrder}). A hanging test that the order
     * moves behind a failing one turns a TIMEOUT into KILLED.
     */
    Result run(Mutant m) throws Engine.Failure, IOException {
        if (m.datalog() != null) {
            if (!config.selection()) return runDatalog(m, testNames, false);
            List<String> selected = coverage.testsFor(m.datalog().row());
            if (selected.isEmpty()) return new Result(m, file(m), position(m), Outcome.NOT_COVERED, "", 0, 0, testCount);
            return runDatalog(m, config.order() ? order.order(selected) : selected, true);
        }
        if (!config.selection()) return run(m, testNames, false);
        List<String> selected = config.order() ? order.order(coverage.testsFor(m.id())) : coverage.testsFor(m.id());
        if (selected.isEmpty())
            return new Result(m, file(m), position(m), Outcome.NOT_COVERED, "", 0, 0, testCount);
        Result r = run(m, selected, true);
        order.record(selected, r);
        return r;
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
            int full = selection ? fullBound(selected, ran, r.field(1)) : ran;
            return new Result(m, file(m), position(m), Outcome.KILLED, r.field(1) + ": " + r.field(2), selected.size(), ran, full);
        } catch (WorkerClient.Timeout e) {
            // The worker was killed; the next one loads the program again.
            startWorker();
            load();
            return new Result(m, file(m), position(m), Outcome.TIMEOUT, "", selected.size(), selected.size() /* not measured: upper bound */, testCount);
        }
    }

    /**
     * An upper bound of the tests a run of all tests would execute for a mutant killed by {@code killer} after the first
     * {@code ran} of {@code sent}: the killer's position in name order, unless a selected test that is before it in name
     * order did not run. That test might fail or hang as well (a hang counts all tests), so then the bound is all tests.
     */
    private int fullBound(List<String> sent, int ran, String killer) {
        int at = testNames.indexOf(killer);
        for (String t : sent.subList(ran, sent.size()))
            if (testNames.indexOf(t) < at) return testCount;
        return at + 1;
    }

    /**
     * Runs a Datalog mutant with the named tests. Killed: a test fails. Invalid: a solve of the changed constraint set threw. Else, if
     * the mutant changed no constraint in any solve, it is not covered (the solves of the tests do not contain it by name); if the
     * models of all solves equal those of the baseline it is equivalent (weak mutation: no test could tell); otherwise it survived.
     */
    private Result runDatalog(Mutant m, List<String> selected, boolean selection) throws Engine.Failure, IOException {
        DatalogMutation d = m.datalog();
        List<String> fields = new ArrayList<>(List.of(d.op(), d.key(), String.valueOf(d.atom())));
        if (selection) fields.addAll(selected);
        try {
            List<Protocol.Message> reply = askTimed(Protocol.encode("dlrun", fields.toArray(new String[0])), RUN_END, timeout);
            Protocol.Message r = reply.get(reply.size() - 1);
            if (!r.verb().equals("result")) throw failed("mutant " + m.id() + " failed", r);
            int applied = 0;
            boolean differs = false;
            for (Protocol.Message x : reply) {
                if (x.verb().equals("dlapplied")) applied = Integer.parseInt(x.field(0));
                if (x.verb().equals("dlmodels") && !x.fields().subList(1, x.fields().size()).equals(baselineModels.get(x.field(0)))) differs = true;
            }
            switch (r.field(0)) {
                case "survived" -> {
                    int ran = Integer.parseInt(r.field(1));
                    int full = selection ? testCount : ran;
                    if (applied == 0)
                        return new Result(m, file(m), position(m), Outcome.NOT_COVERED, "no solve of the tests contains it by name", selected.size(), ran, full);
                    return new Result(m, file(m), position(m), differs ? Outcome.SURVIVED : Outcome.EQUIVALENT, "", selected.size(), ran, full);
                }
                case "invalid" -> {
                    int ran = Integer.parseInt(r.field(3));
                    return new Result(m, file(m), position(m), Outcome.INVALID, r.field(1) + ": " + r.field(2), selected.size(), ran,
                        selection ? fullBound(selected, ran, r.field(1)) : ran);
                }
                default -> {
                    int ran = Integer.parseInt(r.field(3));
                    int full = selection ? fullBound(selected, ran, r.field(1)) : ran;
                    return new Result(m, file(m), position(m), Outcome.KILLED, r.field(1) + ": " + r.field(2), selected.size(), ran, full);
                }
            }
        } catch (WorkerClient.Timeout e) {
            startWorker();
            load();
            return new Result(m, file(m), position(m), Outcome.TIMEOUT, "", selected.size(), selected.size(), testCount);
        }
    }

    private int load() throws Engine.Failure, IOException {
        List<Protocol.Message> reply = ask(
            config.datalogMutants() ? Protocol.encode("load", loadList.toString(), String.valueOf(siteTable.size()), markerJar.toString(), "datalog", "mutants")
                : config.datalog() ? Protocol.encode("load", loadList.toString(), String.valueOf(siteTable.size()), markerJar.toString(), "datalog")
                : Protocol.encode("load", loadList.toString(), String.valueOf(siteTable.size()), markerJar.toString()),
            LOAD_END, SETUP_TIMEOUT_MS);
        Protocol.Message last = reply.get(reply.size() - 1);
        if (!last.verb().equals("loaded")) throw failed("the schemata program does not compile", last);
        if (config.datalog() && last.fields().size() > 1 && last.field(1).equals("no-solver") && !plan.datalogSites().isEmpty() && !warnedNoSolver) {
            warnedNoSolver = true;
            config.progress().accept("warning: datalog: the compiled program has no Fixpoint3 solver class "
                + "(the sources have rules and facts, but nothing solves them, or this Flix version names the class differently): "
                + "every rule and fact reads not covered");
        }
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
