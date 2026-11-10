package dev.flixw.spike;

import ca.uwaterloo.flix.runtime.LoadedProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike engine for #1 part 2 (design option 4). Renders the template into the schemata program, compiles
 * once, loads once, and per mutant sets the switch (system property flixw.mutant) and reruns the tests with
 * a timeout. Then measures the same mutants by one compile per mutant (text replacement of the site).
 *
 * Usage: SchemataRun <fixtureDir> <mutants.tsv> <workDir> <runs> <timeoutMs> [baselineMutants]
 */
public final class SchemataRun {
    static final Pattern SITE = Pattern.compile("@\\{(\\d+)\\|(.*?)\\|(.*?)\\}@");
    static final Pattern TAG = Pattern.compile("/\\*S(\\d+)\\*/");

    record Site(int id, String original, String mutated) {}

    public static void main(String[] a) throws Exception {
        Path fixture = Path.of(a[0]);
        Path mutantsFile = Path.of(a[1]);
        Path work = Path.of(a[2]);
        int runs = Integer.parseInt(a[3]);
        long timeout = Long.parseLong(a[4]);
        int baselineN = a.length > 5 ? Integer.parseInt(a[5]) : 100;
        String tmpl = Files.readString(fixture.resolve("Calc.flix.tmpl"));
        List<Site> sites = new ArrayList<>();
        Matcher m = SITE.matcher(tmpl);
        while (m.find()) sites.add(new Site(Integer.parseInt(m.group(1)), m.group(2), m.group(3)));
        Map<Integer, String[]> expected = new TreeMap<>();
        for (String line : Files.readAllLines(mutantsFile)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t");
            expected.put(Integer.parseInt(f[0]), f);
        }
        Files.createDirectories(work);
        List<Path> common = List.of(fixture.resolve("Mutant.flix"), fixture.resolve("TestCalc.flix"));

        // 0. The original program: all tests pass.
        TreeMap<String, Runnable> orig = load(common, render(tmpl, sites, Set0.NONE, 0), work.resolve("orig"));
        report("original", FlixHost.runAll(orig, timeout, List.of()));

        // 1. Schemata program with every site. A type error names the offending sites (tag /*S<id>*/).
        List<Site> live = new ArrayList<>(sites);
        List<Integer> invalid = new ArrayList<>();
        int compiles = 0;
        long t0 = System.nanoTime();
        TreeMap<String, Runnable> tests = null;
        while (tests == null) {
            compiles++;
            try {
                tests = load(common, render(tmpl, live, Set0.SCHEMATA, 0), work.resolve("schemata"));
            } catch (FlixHost.CompileError e) {
                List<Integer> bad = new ArrayList<>();
                Matcher t = TAG.matcher(e.getMessage());
                while (t.find()) bad.add(Integer.parseInt(t.group(1)));
                System.out.println("schemata compile " + compiles + " failed, sites named in the error: " + bad);
                System.out.println("  first error: " + e.getMessage().lines().findFirst().orElse(""));
                if (bad.isEmpty()) throw e;
                for (int b : bad) { invalid.add(b); live.removeIf(s -> s.id() == b); }
            }
        }
        long setupMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("schemata setup (compile+load, " + compiles + " compile(s)) ms=" + setupMs + " liveSites=" + live.size());
        List<FlixHost.TestResult> base = FlixHost.runAll(tests, timeout, List.of());
        report("schemata, switch off", base);

        // 2. Per mutant: switch + rerun, `runs` times.
        int mismatches = 0;
        Map<Integer, String> schemaOutcome = new TreeMap<>();
        List<Long> switchNanos = new ArrayList<>();   // runs of mutants that did not time out
        for (Site s : sites) {
            String actual;
            long best = 0, total = 0;
            if (invalid.contains(s.id())) {
                actual = "invalid";
            } else {
                actual = null;
                for (int r = 0; r < runs; r++) {
                    if (!System.getProperty("spike.sabotage", "").equals("noswitch")) System.setProperty("flixw.mutant", String.valueOf(s.id()));
                    long t = System.nanoTime();
                    List<FlixHost.TestResult> rs = FlixHost.runAll(tests, timeout, List.of());
                    long d = System.nanoTime() - t;
                    System.setProperty("flixw.mutant", "0");
                    total += d;
                    String o = classify(rs);
                    if (!o.equals("timeout")) switchNanos.add(d);
                    if (actual == null) actual = o;
                    else if (!actual.equals(o)) actual = actual + "/" + o + "(unstable)";
                }
            }
            schemaOutcome.put(s.id(), actual);
            String exp = expected.get(s.id())[1];
            boolean ok = exp.equals(actual);
            if (!ok) mismatches++;
            System.out.printf("site %d expected=%-8s actual=%-8s %s  %s%n", s.id(), exp, actual, ok ? "ok  " : "DIFF",
                    expected.get(s.id())[2]);
        }
        java.util.Collections.sort(switchNanos);
        System.out.printf("schemata: %d switch runs (all %d tests, timeouts excluded), mean %.1f ms, median %.1f ms, min %.1f ms, max %.1f ms%n",
                switchNanos.size(), tests.size(), switchNanos.stream().mapToLong(x -> x).average().orElse(0) / 1e6,
                switchNanos.get(switchNanos.size() / 2) / 1e6, switchNanos.get(0) / 1e6,
                switchNanos.get(switchNanos.size() - 1) / 1e6);

        // 3. Baseline: one compile per mutant (text replacement), the first baselineN sites that are not invalid.
        List<Long> perCompile = new ArrayList<>();
        int done = 0;
        for (Site s : sites) {
            if (done >= baselineN || invalid.contains(s.id()) || schemaOutcome.get(s.id()).equals("timeout")) continue;
            long t = System.nanoTime();
            TreeMap<String, Runnable> mt = load(common, render(tmpl, sites, Set0.TEXT, s.id()), work.resolve("m" + s.id()));
            String o = classify(FlixHost.runAll(mt, timeout, List.of()));
            long d = System.nanoTime() - t;
            perCompile.add(d);
            boolean same = o.equals(schemaOutcome.get(s.id()));
            if (!same) mismatches++;
            System.out.printf("compile-per-mutant site %d outcome=%s (schemata: %s) %s  %.0f ms%n", s.id(), o,
                    schemaOutcome.get(s.id()), same ? "agree" : "DISAGREE", d / 1e6);
            done++;
        }
        System.out.printf("compile per mutant: %d runs, mean %.0f ms, min %.0f ms, max %.0f ms%n", perCompile.size(),
                perCompile.stream().mapToLong(x -> x).average().orElse(0) / 1e6,
                perCompile.stream().mapToLong(x -> x).min().orElse(0) / 1e6,
                perCompile.stream().mapToLong(x -> x).max().orElse(0) / 1e6);
        probes(work);
        System.out.println(mismatches == 0 ? "RESULT: OK" : "RESULT: FAIL " + mismatches + " mismatches");
        System.exit(mismatches == 0 ? 0 : 1);
    }

    /** Effect and purity probes for the Mutant.on wrapper, and a check that a switch change inside one run is seen. */
    static void probes(Path work) throws Exception {
        String head = "mod P {\n    import java.lang.System\n";
        String[][] variants = {
            {"no unsafe: Java call in a pure def",
             head + "    pub def on(n: Int32): Bool = Int32.fromString(System.getProperty(\"flixw.mutant\", \"0\")) |> Option.getWithDefault(0) == n\n}\n"},
            {"effect IO on `on`, used from a pure def",
             head + "    pub def on(n: Int32): Bool \\ IO = Int32.fromString(System.getProperty(\"flixw.mutant\", \"0\")) |> Option.getWithDefault(0) == n\n"
             + "    pub def f(x: Int32): Int32 = if (on(1)) 0 else x\n}\n"},
        };
        for (String[] v : variants) {
            try {
                load(List.of(), v[1], work.resolve("probe"));
                System.out.println("probe [" + v[0] + "]: compiles (unexpected)");
            } catch (FlixHost.CompileError e) {
                String msg = e.getMessage().replaceAll("\\s+", " ");
                System.out.println("probe [" + v[0] + "]: compile error: " + msg.substring(0, Math.min(msg.length(), 260)));
            }
        }
        String src = head + "    def on(n: Int32): Bool =\n        Int32.fromString(unsafe IO { System.getProperty(\"flixw.mutant\", \"0\") }) |> Option.getWithDefault(0) == n\n"
            + "    def f(x: Int32): Int32 = if (on(1)) 100 else x\n"
            + "    def setSwitch(v: String): Unit \\ IO = { let _ = System.setProperty(\"flixw.mutant\", v); () }\n"
            + "    @Test\n    def changeInsideOneRun(): Unit \\ {Assert, IO} =\n"
            + "        let a = f(5);\n        setSwitch(\"1\");\n        let b = f(5);\n        setSwitch(\"0\");\n"
            + "        Assert.assertEq(expected = (5, 100), (a, b))\n}\n";
        TreeMap<String, Runnable> t = load(List.of(), src, work.resolve("probe2"));
        var pr = FlixHost.runAll(t, 5000, List.of()).get(0);
        System.out.println("probe [switch changed inside one run]: " + pr.outcome() + " " + pr.detail()
                + " (expected (5, 100) if the second call were re-evaluated)");
    }

    enum Set0 { NONE, SCHEMATA, TEXT }

    /** NONE: originals; SCHEMATA: switch expression per site; TEXT: only site {@code only} mutated. */
    static String render(String tmpl, List<Site> live, Set0 mode, int only) {
        Matcher m = SITE.matcher(tmpl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            String o = m.group(2), mu = m.group(3);
            String r;
            if (live.stream().noneMatch(s -> s.id() == id)) r = "(" + o + ")";
            else if (mode == Set0.SCHEMATA) r = "(if (Mutant.on(" + id + ")) (" + mu + ") else (" + o + ")) /*S" + id + "*/";
            else if (mode == Set0.TEXT && id == only) r = "(" + mu + ")";
            else r = "(" + o + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static TreeMap<String, Runnable> load(List<Path> common, String calc, Path dir) throws Exception {
        Files.createDirectories(dir);
        Path calcFile = dir.resolve("Calc.flix");
        Files.writeString(calcFile, calc);
        List<Path> files = new ArrayList<>(common);
        files.add(calcFile);
        LoadedProgram p = FlixHost.load(FlixHost.compile(FlixHost.newFlix(files)));
        return FlixHost.tests(p);
    }

    static String classify(List<FlixHost.TestResult> rs) {
        if (rs.stream().anyMatch(r -> r.outcome() == FlixHost.Outcome.FAIL)) return "killed";
        if (rs.stream().anyMatch(r -> r.outcome() == FlixHost.Outcome.ERROR)) return "killed";
        if (rs.stream().anyMatch(r -> r.outcome() == FlixHost.Outcome.TIMEOUT)) return "timeout";
        return "survived";
    }

    static void report(String what, List<FlixHost.TestResult> rs) {
        long pass = rs.stream().filter(r -> r.outcome() == FlixHost.Outcome.PASS).count();
        System.out.println(what + ": " + pass + "/" + rs.size() + " tests pass");
        if (pass != rs.size()) {
            rs.stream().filter(r -> r.outcome() != FlixHost.Outcome.PASS).forEach(r -> System.out.println("  " + r.name() + " " + r.outcome() + " " + r.detail()));
            System.exit(1);
        }
    }
}
