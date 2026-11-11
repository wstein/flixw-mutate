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
 * <li>{@code sites <listfile>}: type-checks the files of the list and answers one {@code site} message per
 *   site and {@code done <left out>}; {@code compile-error <message>...} if the program does not compile.
 * <li>{@code load <listfile> <mutants> <markerjar>}: compiles and loads the program with the marker jar
 *   (written there if it does not exist; the switches are 1 to {@code mutants}); {@code loaded <tests>} or
 *   {@code compile-error}.
 * <li>{@code run <n> [<test>...]}: sets the switch to mutant {@code n} (0: none), runs the named tests in the
 *   given order (all tests in name order without names) and stops at the first failure:
 *   {@code result survived} or {@code result killed <test> <description>}.
 * <li>{@code baseline [<test>...]}: runs like {@code run 0} with the markers recording: one
 *   {@code covered <test> <switch>...} message per test, then the result.
 * </ul>
 * A list file has one line per file: {@code M<TAB>path} for a file whose sites are wanted, {@code F<TAB>path}
 * for a file that is only compiled.
 */
public final class Worker {
    private final FlixApi api;
    private final PrintStream out;
    private List<FlixApi.TestCase> tests = List.of();

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
            case "sites" -> sites(readList(Path.of(m.field(0))));
            case "load" -> load(readList(Path.of(m.field(0))), Integer.parseInt(m.field(1)), Path.of(m.field(2)));
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

    private void sites(Map<Path, Boolean> files) throws IOException {
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
        out.println(Protocol.encode("done", String.valueOf(r.skipped())));
    }

    private void load(Map<Path, Boolean> files, int mutants, Path markerJar) throws IOException {
        if (!Files.exists(markerJar)) MarkerJar.write(markerJar);
        MarkerState.publish(mutants);
        MarkerState.set(0, false);
        Object r = api.compileAndLoad(new ArrayList<>(files.keySet()), List.of(markerJar));
        if (r instanceof FlixApi.Failure f) {
            compileError(f);
            return;
        }
        tests = api.tests((FlixApi.Program) r).stream().filter(t -> !t.skip()).toList();
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
        for (FlixApi.TestCase t : selected) {
            if (record) MarkerState.clear();
            try {
                t.run().run();
            } catch (RuntimeException | Error e) {
                out.println(Protocol.encode("result", "killed", t.name(), describe(e)));
                return;
            }
            if (record) {
                String[] fields = new String[MarkerState.hit().size() + 1];
                fields[0] = t.name();
                int i = 1;
                for (int id : MarkerState.hit()) fields[i++] = String.valueOf(id);
                out.println(Protocol.encode("covered", fields));
            }
        }
        out.println(Protocol.encode("result", "survived"));
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
