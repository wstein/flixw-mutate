package dev.flixw.mutate;

import ca.uwaterloo.flix.runtime.LoadedProgram;
import dev.flixw.spike.CovState;
import dev.flixw.spike.FlixHost;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Spike for flixw-mutate#16: per-mutant test selection from marker hits of a baseline run.
 *
 * <p>Sites, rendering and the switch module are the engine's own ({@link SiteFinder}, {@link Schemata}, unchanged,
 * compiled from src/main). The marker variants only replace the body of {@code FlixwMutant.on}. The program is
 * compiled once per variant and every test is run one by one.
 *
 * <p>Usage: CoverageRun &lt;projectDir&gt; &lt;workDir&gt; &lt;support.jar&gt; &lt;timeoutMs&gt; &lt;reps&gt;
 * Properties: spike.sabotage=nomarks (markers never record), noreset (the marker state is not cleared before each test),
 * countall (every survivor counts as "without a run"); each makes the run fail.
 */
public final class CoverageRun {
    /** The report channel; the tests print their assertion messages to stdout, which goes to stderr here. */
    static final java.io.PrintStream OUT = System.out;
    static long executed;

    record Out(String kind, String test) {
        @Override public String toString() { return test.isEmpty() ? kind : kind + "(" + test + ")"; }
    }

    public static void main(String[] a) throws Exception {
        System.setOut(System.err);
        Path project = Path.of(a[0]).toAbsolutePath().normalize();
        Path work = Path.of(a[1]);
        Path support = Path.of(a[2]);
        long timeout = Long.parseLong(a[3]);
        int reps = Integer.parseInt(a[4]);
        boolean sabotage = "nomarks".equals(System.getProperty("spike.sabotage"));
        boolean noreset = "noreset".equals(System.getProperty("spike.sabotage"));
        boolean countall = "countall".equals(System.getProperty("spike.sabotage"));
        List<String> failures = new ArrayList<>();
        Files.createDirectories(work);
        CovState.publish();
        List<Path> src = flix(project.resolve("src"));
        List<Path> tests = flix(project.resolve("test"));

        // 1. Sites and rendering, as Engine.execute does it.
        FlixApi api = new FlixApi(CoverageRun.class.getClassLoader());
        List<Path> all = new ArrayList<>(src);
        all.addAll(tests);
        FlixApi.Checked checked = api.check(all);
        if (checked.failure() != null) throw new IllegalStateException(String.join("\n", checked.failure().messages()));
        Map<String, String> text = new TreeMap<>();
        for (Path p : src) text.put(p.toString(), Files.readString(p, StandardCharsets.UTF_8));
        SiteFinder.Result found = SiteFinder.find(api, checked.root(), text);
        List<Mutant> mutants = new ArrayList<>();
        Map<Integer, String> where = new TreeMap<>();
        List<Path> rendered = new ArrayList<>();
        for (Path p : src) {
            List<Site> sites = found.sites().getOrDefault(p.toString(), List.of());
            Path target = p;
            if (!sites.isEmpty()) {
                Schemata.Rendered r = Schemata.render(text.get(p.toString()), sites, mutants.size() + 1);
                target = work.resolve("rendered").resolve(project.relativize(p));
                Files.createDirectories(target.getParent());
                Files.writeString(target, r.text(), StandardCharsets.UTF_8);
                for (Mutant m : r.mutants())
                    where.put(m.id(), project.relativize(p) + ":" + Schemata.position(text.get(p.toString()), m.site().whole().start()));
                mutants.addAll(r.mutants());
            }
            rendered.add(target);
        }
        OUT.println("project " + project.getFileName() + ": " + src.size() + " src files, " + tests.size() + " test files, "
            + found.sites().values().stream().mapToInt(List::size).sum() + " sites, " + mutants.size() + " mutants (switch ids)");

        // 2. Marker variants: compile and load once each; baseline suite with every switch off.
        List<Path> jars = List.of(support);
        List<Path> recFiles = null;
        Map<String, TreeMap<String, Runnable>> programs = new TreeMap<>();
        for (String variant : List.of("original", "prop", "static", "rec", "proprec")) {
            Path dir = work.resolve("v-" + variant);
            Files.createDirectories(dir);
            List<Path> files = new ArrayList<>(variant.equals("original") ? src : rendered);
            if (!variant.equals("original")) {
                Path mod = dir.resolve("FlixwMutant.flix");
                Files.writeString(mod, module(variant), StandardCharsets.UTF_8);
                files.add(mod);
            }
            files.addAll(tests);
            long t0 = System.nanoTime();
            TreeMap<String, Runnable> loaded = load(files, jars);
            OUT.printf("variant %-8s compile+load %d ms, %d tests%n", variant, ms(t0), loaded.size());
            programs.put(variant, loaded);
            if (variant.equals("rec")) recFiles = files;
        }
        List<String> names = new ArrayList<>(programs.get("rec").keySet());

        // 3. Cost of the marker with every switch off: suite and the hot test, median of reps (2 warm-up runs).
        OUT.println("-- marker cost, switches off, " + reps + " reps (median ms; warm-up 2)");
        String hot = names.stream().filter(n -> n.contains("testHot")).findFirst().orElse(null);
        for (String variant : List.of("original", "prop", "static", "rec", "proprec")) {
            CovState.STATE[1] = variant.equals("rec") && !sabotage ? 1 : 0;
            System.clearProperty("flixw.mutant");
            List<Double> suite = new ArrayList<>();
            List<Double> hotMs = new ArrayList<>();
            for (int r = 0; r < reps + 2; r++) {
                double total = 0;
                double h = 0;
                for (String n : names) {
                    long t = System.nanoTime();
                    programs.get(variant).get(n).run();
                    double d = (System.nanoTime() - t) / 1e6;
                    total += d;
                    if (n.equals(hot)) h = d;
                }
                if (r >= 2) { suite.add(total); hotMs.add(h); }
            }
            OUT.printf("cost %-8s suite %.1f ms%s%n", variant, median(suite), hot != null ? String.format(", testHot %.1f ms", median(hotMs)) : "");
            CovState.STATE[1] = 0;
        }

        // 4. Hits per test: variant rec (Java static marker) and, as a cross-check, proprec (JDK only).
        CovState.STATE[1] = sabotage ? 0 : 1;
        Map<String, TreeSet<Integer>> hitsRec = new TreeMap<>();
        Map<String, Integer> calls = new TreeMap<>();
        for (String n : names) {
            if (!noreset) CovState.clear();
            CovState.STATE[2] = 0;
            programs.get("rec").get(n).run();
            calls.put(n, CovState.STATE[2]);
            TreeSet<Integer> s = new TreeSet<>();
            for (int i = 0; i < CovState.HIT.length; i++) if (CovState.HIT[i]) s.add(i);
            hitsRec.put(n, s);
        }
        CovState.STATE[1] = 0;
        OUT.println("-- marker calls per test (variant rec, baseline): total " + calls.values().stream().mapToLong(x -> x).sum()
            + ", largest " + calls.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> shortName(e.getKey()) + "=" + e.getValue()).orElse(""));
        // Order and class initialisation: a fresh load of the same program, recording from the load on, tests in
        // reverse order. A site evaluated once at class initialisation or cached would show up in another test.
        CovState.clear();
        CovState.STATE[2] = 0;
        CovState.STATE[1] = sabotage ? 0 : 1;
        TreeMap<String, Runnable> fresh = load(recFiles, jars);
        int atLoad = 0;
        for (boolean h : CovState.HIT) if (h) atLoad++;
        Map<String, TreeSet<Integer>> hitsRev = new TreeMap<>();
        for (String n : new ArrayList<>(names).reversed()) {
            if (!noreset) CovState.clear();
            fresh.get(n).run();
            TreeSet<Integer> s = new TreeSet<>();
            for (int i = 0; i < CovState.HIT.length; i++) if (CovState.HIT[i]) s.add(i);
            hitsRev.put(n, s);
        }
        CovState.STATE[1] = 0;
        OUT.println("-- fresh load, reverse order: sites evaluated while compiling and loading: " + atLoad
            + "; hit sets equal to the first pass for every test: " + hitsRev.equals(hitsRec));
        if (!hitsRev.equals(hitsRec)) { OUT.println("   DIFF forward vs reverse"); failures.add("reverse order"); }
        Map<String, TreeSet<Integer>> hitsProp = new TreeMap<>();
        for (String n : names) {
            clearHitProps();
            programs.get("proprec").get(n).run();
            TreeSet<Integer> s = new TreeSet<>();
            for (String k : System.getProperties().stringPropertyNames())
                if (k.startsWith("flixw.hit.")) s.add(Integer.parseInt(k.substring("flixw.hit.".length())));
            hitsProp.put(n, s);
        }
        clearHitProps();
        OUT.println("-- hit sets: rec (Java static) equals proprec (System properties) for every test: " + hitsRec.equals(hitsProp));
        if (!hitsRec.equals(hitsProp)) { OUT.println("   DIFF rec vs proprec"); failures.add("rec vs proprec"); }

        // 5. Tests per switch id, and per site.
        Map<Integer, List<String>> byId = new TreeMap<>();
        for (Mutant m : mutants) byId.put(m.id(), new ArrayList<>());
        hitsRec.forEach((n, s) -> { for (int id : s) if (byId.containsKey(id)) byId.get(id).add(n); else OUT.println("   hit of unknown id " + id + " by " + n); });
        int unreached = 0;
        List<Integer> counts = new ArrayList<>();
        StringBuilder tsv = new StringBuilder("id\tsite\tmutation\ttests\tnames\n");
        for (Mutant m : mutants) {
            List<String> ts = byId.get(m.id());
            counts.add(ts.size());
            if (ts.isEmpty()) unreached++;
            tsv.append(m.id()).append('\t').append(where.get(m.id())).append('\t').append(m.from()).append(" -> ").append(m.to())
                .append('\t').append(ts.size()).append('\t').append(String.join(",", ts.stream().map(CoverageRun::shortName).toList())).append('\n');
        }
        Files.writeString(work.resolve("tests-per-mutant-" + project.getFileName() + ".tsv"), tsv.toString());
        Collections_sort(counts);
        double mean = counts.stream().mapToInt(x -> x).average().orElse(0);
        OUT.printf("-- tests per mutant (switch id), of %d tests: min %d, median %d, mean %.2f, max %d; reached by none: %d of %d%n",
            names.size(), counts.get(0), counts.get(counts.size() / 2), mean, counts.get(counts.size() - 1), unreached, mutants.size());
        OUT.println("   histogram (tests -> mutants): " + histogram(counts));
        OUT.printf("   mean share of the suite per mutant: %.1f %%%n", 100 * mean / names.size());

        // 6. Per mutant: full run (all tests in name order, stop at the first failure, as Worker.run) against the
        //    selected run (only the tests that hit the id). Then every unselected test alone under the mutant:
        //    it must pass (a failure is a missed attribution).
        OUT.println("-- mutants: full run against selected run (timeout " + timeout + " ms per test)");
        TreeMap<String, Runnable> prog = programs.get("rec");
        CovState.STATE[1] = 0;
        int equal = 0, differ = 0, missed = 0, pairs = 0;
        long fullNT = 0, selNT = 0, fullAll = 0, selAll = 0, fullSurv = 0, selSurv = 0, fullRuns = 0, selRuns = 0, fullSurvRuns = 0, selSurvRuns = 0;
        int surv = 0, killed = 0, timeouts = 0, survNoRun = 0;
        long baselineFull = 0;
        executed = 0;
        for (String n : names) baselineFull += time(prog.get(n));
        for (Mutant m : mutants) {
            List<String> sel = byId.get(m.id());
            long e0 = executed;
            long t0 = System.nanoTime();
            Out full = run(m.id(), names, prog, timeout);
            long fullMs = (System.nanoTime() - t0) / 1_000_000;
            long e1 = executed;
            long t1 = System.nanoTime();
            Out chosen = sel.isEmpty() ? new Out("survived", "") : run(m.id(), sel, prog, timeout);
            long selMs = (System.nanoTime() - t1) / 1_000_000;
            long e2 = executed;
            fullRuns += e1 - e0; selRuns += e2 - e1;
            boolean same = full.equals(chosen);
            if (same) equal++; else { differ++; OUT.println("   DIFF mutant " + m.id() + " " + where.get(m.id()) + " " + m.from() + " -> " + m.to() + ": full " + full + ", selected " + chosen); }
            fullAll += fullMs; selAll += selMs;
            if (!full.kind().equals("timeout")) { fullNT += fullMs; selNT += selMs; }
            switch (full.kind()) {
                case "survived" -> { surv++; fullSurv += fullMs; selSurv += selMs; fullSurvRuns += e1 - e0; selSurvRuns += e2 - e1; if (sel.isEmpty() || countall) survNoRun++; }
                case "killed" -> killed++;
                default -> timeouts++;
            }
            for (String n : names) {
                if (sel.contains(n)) continue;
                pairs++;
                Out o = run(m.id(), List.of(n), prog, timeout);
                if (!o.kind().equals("survived")) { missed++; OUT.println("   MISSED attribution: mutant " + m.id() + " changes " + n + ": " + o); }
            }
        }
        OUT.printf("   full vs selected outcome (kind and first killing test): equal %d, different %d (of %d mutants)%n", equal, differ, mutants.size());
        OUT.printf("   unselected (mutant, test) pairs run alone under the mutant: %d, of which not passing: %d%n", pairs, missed);
        OUT.printf("   outcomes (full run): survived %d (%d without a run: no test reaches the site), killed %d, timeout %d%n", surv, survNoRun, killed, timeouts);
        OUT.printf("   baseline suite, one pass without mutant: %d ms%n", baselineFull / 1_000_000);
        OUT.printf("   run time over all mutants: full %d ms, selected %d ms; survivors only: full %d ms, selected %d ms%n", fullAll, selAll, fullSurv, selSurv);
        OUT.printf("   run time without the timeout mutants: full %d ms, selected %d ms%n", fullNT, selNT);
        OUT.printf("   test executions over all mutants: full %d, selected %d; survivors only: full %d, selected %d%n", fullRuns, selRuns, fullSurvRuns, selSurvRuns);
        // Consistency of the numbers reported above.
        int histSum = counts.size();
        long hitSum = 0;
        for (int c : counts) hitSum += c;
        long perTest = hitsRec.values().stream().mapToLong(x -> x.stream().filter(byId::containsKey).count()).sum();
        if (histSum != mutants.size()) failures.add("distribution covers " + histSum + " of " + mutants.size() + " mutants");
        if (hitSum != perTest) failures.add("tests per mutant sum " + hitSum + " differs from the hits per test " + perTest);
        if (survNoRun != unreached) failures.add("survived without a run " + survNoRun + " differs from mutants reached by no test " + unreached);
        boolean ok = differ == 0 && missed == 0 && failures.isEmpty();
        OUT.println(ok ? "RESULT: OK" : "RESULT: FAIL " + differ + " different, " + missed + " missed" + (failures.isEmpty() ? "" : ", checks: " + failures));
        System.exit(ok ? 0 : 1);
    }

    /** Runs the tests in order on a worker thread with a timeout; the switch is set for the call. */
    static Out run(int id, List<String> tests, TreeMap<String, Runnable> prog, long timeout) {
        CovState.STATE[0] = id;
        try {
            for (String n : tests) {
                executed++;
                FlixHost.TestResult r = FlixHost.runOne(n, prog.get(n), timeout);
                if (r.outcome() == FlixHost.Outcome.TIMEOUT) {
                    CovState.STATE[0] = 0; // the runaway loop reads the switch on every iteration and ends
                    FlixHost.awaitAbandoned(10_000);
                    return new Out("timeout", shortName(n));
                }
                if (r.outcome() != FlixHost.Outcome.PASS) return new Out("killed", shortName(n));
            }
            return new Out("survived", "");
        } finally {
            CovState.STATE[0] = 0;
        }
    }

    static long time(Runnable r) { long t = System.nanoTime(); r.run(); return System.nanoTime() - t; }

    static String shortName(String n) { int i = n.lastIndexOf('.'); return i < 0 ? n : n.substring(i + 1); }

    static void clearHitProps() {
        Properties p = System.getProperties();
        for (String k : p.stringPropertyNames()) if (k.startsWith("flixw.hit.")) p.remove(k);
    }

    static TreeMap<String, Runnable> load(List<Path> files, List<Path> jars) throws Exception {
        LoadedProgram p = FlixHost.load(FlixHost.compile(FlixHost.newFlix(files, jars)));
        TreeMap<String, Runnable> out = new TreeMap<>();
        scala.jdk.javaapi.CollectionConverters.asJava(p.tests()).forEach((sym, fn) -> {
            if (!fn.skip()) out.put(sym.toString(), () -> fn.run().apply());
        });
        return out;
    }

    /** The engine's switch module with the body of {@code on} replaced. */
    static String module(String variant) {
        String base = Schemata.module();
        int a = base.indexOf("    pub def on(n: Int32): Bool =");
        int b = base.indexOf("\n\n", a);
        String body = switch (variant) {
            case "prop" -> base.substring(a, b);
            case "static" -> "    pub def on(n: Int32): Bool = unsafe IO { Cov.off(n) }";
            case "rec" -> "    pub def on(n: Int32): Bool = unsafe IO { Cov.on(n) }";
            case "proprec" -> """
                    pub def on(n: Int32): Bool =
                        let cur = Int32.fromString(unsafe IO { System.getProperty("flixw.mutant", "0") }) |> Option.getWithDefault(0);
                        let r = unsafe IO { System.setProperty("flixw.hit.${n}", "1") };
                        if (Objects.isNull(r)) cur == n else cur == n""".replaceAll("(?m)^ {16}", "    ");
            default -> throw new IllegalArgumentException(variant);
        };
        String head = base.substring(0, a).replace("    import java.lang.System\n",
            "    import java.lang.System\n    import java.util.Objects\n    import dev.flixw.spike.Cov\n");
        return head + body + base.substring(b);
    }

    static List<Path> flix(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".flix")).map(Path::toAbsolutePath).sorted().toList();
        }
    }

    static long ms(long since) { return (System.nanoTime() - since) / 1_000_000; }

    static double median(List<Double> v) { List<Double> c = new ArrayList<>(v); java.util.Collections.sort(c); return c.get(c.size() / 2); }

    static void Collections_sort(List<Integer> l) { java.util.Collections.sort(l); }

    static String histogram(List<Integer> counts) {
        TreeMap<Integer, Integer> h = new TreeMap<>();
        for (int c : counts) h.merge(c, 1, Integer::sum);
        return h.toString();
    }
}
