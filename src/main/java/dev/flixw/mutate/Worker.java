package dev.flixw.mutate;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The worker JVM: the Flix compiler is on its class path. It answers the requests of {@link WorkerClient}
 * on stdin and stdout; everything the tests print goes to stderr. It ends when its stdin closes, also while
 * a test is running.
 *
 * <p>Requests (tab-separated, see {@link Protocol}):
 * <ul>
 * <li>{@code sites <listfile> [regions] [datalog]}: type-checks the files of the list and answers one {@code site} message per
 *   site and {@code done <left out>}; {@code compile-error <message>...} if the program does not compile. With
 *   {@code regions} it also answers one {@code region} message per coverage region and one {@code exec} message per
 *   expression start of the {@code M} files, before {@code done}. With {@code datalog} it answers one {@code dlsite}
 *   message per Datalog rule and fact of the {@code M} files, before {@code done}.
 * <li>{@code load <listfile> <switches> <markerjar> [datalog]}: compiles and loads the program with the marker jar
 *   (written there if it does not exist; the switches are 1 to {@code switches}: the ids of the mutants and of the
 *   regions); {@code loaded <tests>} or {@code compile-error}. With {@code datalog} the compiled Fixpoint3 solver
 *   calls the hook module {@code FlixwDatalog} of the list ({@link DatalogHook}) on every solve.
 * <li>{@code run <n> [<test>...]}: sets the switch to mutant {@code n} (0: none), runs the named tests in the
 *   given order (all tests in name order without names) and stops at the first failure:
 *   {@code result survived <tests run>} or {@code result killed <test> <description> <tests run>}.
 * <li>{@code baseline [<test>...]}: runs like {@code run 0} with the markers recording: one
 *   {@code covered <test> <switch>...} message per test, then, if the program was loaded with {@code datalog}, one
 *   {@code dlhit <test> R <rule signature>} or {@code dlhit <test> F <fact text>} message per rule and fact of each
 *   solve of the test, then the result.
 * </ul>
 * A list file has one line per file: {@code M<TAB>path} for a file whose sites are wanted, {@code F<TAB>path}
 * for a file that is only compiled.
 */
public final class Worker {
    private static final String DATALOG_MODULE = "FlixwDatalog";

    private final FlixApi api;
    private final PrintStream out;
    private List<FlixApi.TestCase> tests = List.of();
    private boolean datalog;

    private Worker(FlixApi api, PrintStream out) {
        this.api = api;
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) requests.add(line);
            } catch (IOException e) {
                // fall through: the engine is gone
            }
            Runtime.getRuntime().halt(0);
        }, "worker-stdin");
        reader.setDaemon(true);
        reader.start();
        Worker w = new Worker(new FlixApi(Worker.class.getClassLoader()), protocol);
        while (true) {
            Protocol.Message m = Protocol.decode(requests.take());
            if (m.verb().equals("quit")) return;
            try {
                w.handle(m);
            } catch (RuntimeException | Error e) {
                protocol.println(Protocol.encode("error", describe(e)));
            }
        }
    }

    private void handle(Protocol.Message m) throws IOException {
        switch (m.verb()) {
            case "sites" -> sites(readList(Path.of(m.field(0))), m.fields().subList(1, m.fields().size()));
            case "load" -> load(readList(Path.of(m.field(0))), Integer.parseInt(m.field(1)), Path.of(m.field(2)),
                m.fields().size() > 3 && m.field(3).equals("datalog"));
            case "run" -> run(Integer.parseInt(m.field(0)), m.fields().subList(1, m.fields().size()));
            case "baseline" -> baseline(m.fields());
            default -> out.println(Protocol.encode("error", "unknown request " + m.verb()));
        }
    }

    /** The files of a list file, in order; the flag is true for {@code M} lines. */
    private static Map<Path, Boolean> readList(Path list) throws IOException {
        Map<Path, Boolean> files = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t", 2);
            files.put(Path.of(f[1]), f[0].equals("M"));
        }
        return files;
    }

    private void sites(Map<Path, Boolean> files, List<String> flags) throws IOException {
        boolean regions = flags.contains("regions");
        boolean datalog = flags.contains("datalog");
        FlixApi.Checked checked = api.check(new ArrayList<>(files.keySet()));
        if (checked.failure() != null) {
            compileError(checked.failure());
            return;
        }
        Map<String, String> mutable = new TreeMap<>();
        for (Map.Entry<Path, Boolean> e : files.entrySet())
            if (e.getValue()) mutable.put(e.getKey().toString(), Files.readString(e.getKey(), StandardCharsets.UTF_8));
        SiteFinder.Result r = SiteFinder.find(api, checked.root(), mutable);
        for (List<Site> list : r.sites().values()) for (Site s : list) out.println(Protocol.encodeSite(s));
        if (regions) {
            RegionFinder.Result found = RegionFinder.find(api, checked.root(), mutable);
            for (Region g : found.regions()) out.println(Protocol.encodeRegion(g));
            for (Exec e : found.execs()) out.println(Protocol.encodeExec(e));
        }
        if (datalog) {
            DatalogFinder.Result found = DatalogFinder.find(api, checked.root(), mutable);
            for (DatalogSite d : found.sites()) out.println(Protocol.encodeDatalogSite(d));
        }
        out.println(Protocol.encode("done", String.valueOf(r.skipped())));
    }

    private void load(Map<Path, Boolean> files, int switches, Path markerJar, boolean hookSolver) throws IOException {
        if (!Files.exists(markerJar)) MarkerJar.write(markerJar);
        MarkerState.publish(switches);
        MarkerState.set(0, false);
        datalog = hookSolver;
        DatalogState.clear();
        Object r = api.compileAndLoad(new ArrayList<>(files.keySet()), List.of(markerJar), hookSolver);
        if (r instanceof FlixApi.Failure f) {
            compileError(f);
            return;
        }
        // the hook's own @Test keeps the hook in the program; it is not a test of the project
        tests = api.tests((FlixApi.Program) r).stream().filter(t -> !t.skip() && !t.name().startsWith(DATALOG_MODULE + ".")).toList();
        out.println(Protocol.encode("loaded", String.valueOf(tests.size())));
    }

    private void compileError(FlixApi.Failure failure) {
        out.println(Protocol.encode("compile-error", failure.messages().toArray(new String[0])));
    }

    /** The tests to run: the named ones in the given order, or all in name order. */
    private List<FlixApi.TestCase> select(List<String> names) {
        if (names.isEmpty()) return tests;
        Map<String, FlixApi.TestCase> byName = new java.util.HashMap<>();
        for (FlixApi.TestCase t : tests) byName.put(t.name(), t);
        List<FlixApi.TestCase> selected = new ArrayList<>();
        for (String n : names) {
            FlixApi.TestCase t = byName.get(n);
            if (t == null) throw new IllegalArgumentException("unknown test " + n);
            selected.add(t);
        }
        return selected;
    }

    private void run(int mutant, List<String> names) {
        List<FlixApi.TestCase> selected = select(names);
        MarkerState.set(mutant, false);
        try {
            runAll(selected, false);
        } finally {
            MarkerState.set(0, false);
        }
    }

    private void baseline(List<String> names) {
        List<FlixApi.TestCase> selected = select(names);
        MarkerState.set(0, true);
        try {
            runAll(selected, true);
        } finally {
            MarkerState.set(0, false);
        }
    }

    private void runAll(List<FlixApi.TestCase> selected, boolean record) {
        int ran = 0;
        for (FlixApi.TestCase t : selected) {
            if (record) {
                MarkerState.clear();
                DatalogState.clear();
            }
            ran++;
            try {
                t.run().run();
            } catch (RuntimeException | Error e) {
                out.println(Protocol.encode("result", "killed", t.name(), describe(e), String.valueOf(ran)));
                return;
            }
            if (record) {
                String[] fields = new String[MarkerState.hit().size() + 1];
                fields[0] = t.name();
                int i = 1;
                for (int id : MarkerState.hit()) fields[i++] = String.valueOf(id);
                out.println(Protocol.encode("covered", fields));
                if (datalog) {
                    DatalogState.Solves solves = DatalogState.read();
                    for (String rule : solves.rules()) out.println(Protocol.encode("dlhit", t.name(), "R", rule));
                    for (String fact : solves.facts()) out.println(Protocol.encode("dlhit", t.name(), "F", fact));
                }
            }
        }
        out.println(Protocol.encode("result", "survived", String.valueOf(ran)));
    }

    /** One line for a failure: class and first message line without terminal colour codes, at most 160 chars. */
    static String describe(Throwable t) {
        String message = t.getMessage() == null ? "" : t.getMessage().replaceAll("\u001B\\[[0-9;]*m", "");
        int nl = message.indexOf('\n');
        if (nl >= 0) message = message.substring(0, nl);
        String text = message.isBlank() ? t.getClass().getName() : t.getClass().getName() + ": " + message.strip();
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }
}
