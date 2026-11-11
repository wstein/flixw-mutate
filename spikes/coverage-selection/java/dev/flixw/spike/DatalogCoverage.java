package dev.flixw.spike;

import ca.uwaterloo.flix.runtime.CompilationResult;
import ca.uwaterloo.flix.runtime.LoadedProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Spike for flixw-mutate#16, Datalog part. A Datalog mutant is not a schemata site: the hook of the
 * solver-hook spike (route b, patched Fixpoint3/Solver.flix, unchanged) rewrites the rule vector of every
 * solve. The marker here is the hook's own record of the solves a test makes (rule count and body atoms per
 * rule, property flixw.solve.N.kinds), read after each test run alone. A test reaches the mutant
 * (op, rule r, atom a) if one of its solves has rule r (and body atom a).
 *
 * Usage (through CovLauncher): ... &lt;fixture-dir&gt; &lt;max-mutants&gt; &lt;timeout-ms&gt;
 */
public final class DatalogCoverage {
    record PerTest(String name, FlixHost.Outcome outcome, List<String> kinds, List<String> models, long nanos) {}

    record Mut(String op, int rule, int atom) {
        @Override public String toString() { return op + " r" + rule + (op.equals("drop-rule") ? "" : " a" + atom); }
    }

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args[4]);
        int max = Integer.parseInt(args[5]);
        long timeout = Long.parseLong(args[6]);
        System.setProperty("spike.requireSolve", "false");
        java.io.PrintStream out = System.out;
        System.setOut(System.err); // the tests print their assertion messages to stdout

        List<Path> files = new ArrayList<>();
        for (String d : new String[] {"src", "test"}) {
            if (!Files.isDirectory(fixture.resolve(d))) continue;
            try (Stream<Path> s = Files.walk(fixture.resolve(d))) {
                s.filter(p -> p.toString().endsWith(".flix")).sorted().forEach(files::add);
            }
        }
        long t0 = System.nanoTime();
        CompilationResult cr = FlixHost.compile(FlixHost.newFlix(files, List.of()));
        LoadedProgram program = FlixHost.load(cr);
        long compileMs = SolverHookRun.ms(t0);
        TreeMap<String, Runnable> tests = FlixHost.tests(program);
        out.println("== " + fixture.getFileName() + ": " + tests.size() + " tests, compile+load " + compileMs + " ms");
        if (tests.isEmpty()) { out.println("RESULT: NO-TESTS"); return; }

        // 1. Baseline, every test alone, hook active, counters reset before each test.
        List<PerTest> base = new ArrayList<>();
        for (Map.Entry<String, Runnable> e : tests.entrySet()) {
            SolverHookRun.reset();
            FlixHost.TestResult r = FlixHost.runOne(e.getKey(), e.getValue(), timeout);
            int solves = SolverHookRun.intProp("flixw.solves");
            List<String> kinds = new ArrayList<>();
            for (int k = 0; k < solves; k++) kinds.add(System.getProperty("flixw.solve." + k + ".kinds", ""));
            base.add(new PerTest(e.getKey(), r.outcome(), kinds, SolverHookRun.models(), r.nanos()));
        }
        boolean allPass = base.stream().allMatch(p -> p.outcome() == FlixHost.Outcome.PASS);
        int withSolve = (int) base.stream().filter(p -> !p.kinds().isEmpty()).count();
        long solveCount = base.stream().mapToLong(p -> p.kinds().size()).sum();
        out.println("baseline: tests passing " + base.stream().filter(p -> p.outcome() == FlixHost.Outcome.PASS).count() + "/" + base.size()
            + ", tests with a solve of their own " + withSolve + ", solves " + solveCount);
        if (!allPass || withSolve == 0) { out.println("RESULT: SKIP " + (allPass ? "no solve" : "baseline fails")); return; }

        // 2. Mutants: every (op, rule, atom) of at least one solve, thinned by stride to max.
        TreeSet<String> seen = new TreeSet<>();
        List<Mut> all = new ArrayList<>();
        for (PerTest p : base) for (String kinds : p.kinds()) {
            String[] rules = kinds.isEmpty() ? new String[0] : kinds.split(",", -1);
            for (int r = 0; r < rules.length; r++) {
                if (seen.add("drop-rule " + r + " 0")) all.add(new Mut("drop-rule", r, 0));
                for (int a = 0; a < rules[r].length(); a++) {
                    if (seen.add("drop-atom " + r + " " + a)) all.add(new Mut("drop-atom", r, a));
                    if (rules[r].charAt(a) != '?' && seen.add("flip-negation " + r + " " + a)) all.add(new Mut("flip-negation", r, a));
                }
            }
        }
        int stride = Math.max(1, (all.size() + max - 1) / max);
        List<Mut> muts = new ArrayList<>();
        for (int i = 0; i < all.size(); i += stride) muts.add(all.get(i));
        // An unreachable probe: no solve has rule 99.
        muts.add(new Mut("drop-rule", 99, 0));
        out.println("mutants: " + all.size() + " distinct (op, rule, atom), running " + (muts.size() - 1) + " by stride " + stride + " plus 1 probe (rule 99)");

        // Over-selection: the boundary mutants just beyond what any solve has (the first missing rule, the first
        // missing body atom of each rule) are reached by no test. A reach test that selects too much shows here.
        int maxRules = 0;
        TreeMap<Integer, Integer> maxAtoms = new TreeMap<>();
        for (PerTest p : base) for (String kinds : p.kinds()) {
            String[] rules = kinds.isEmpty() ? new String[0] : kinds.split(",", -1);
            maxRules = Math.max(maxRules, rules.length);
            for (int r = 0; r < rules.length; r++) maxAtoms.merge(r, rules[r].length(), Math::max);
        }
        List<Mut> beyond = new ArrayList<>();
        beyond.add(new Mut("drop-rule", maxRules, 0));
        for (Map.Entry<Integer, Integer> e : maxAtoms.entrySet()) {
            beyond.add(new Mut("drop-atom", e.getKey(), e.getValue()));
            beyond.add(new Mut("flip-negation", e.getKey(), e.getValue()));
        }
        int overSelected = 0;
        for (Mut m : beyond) for (PerTest p : base) if (reaches(p, m)) { overSelected++; out.println("  OVER-SELECTED " + m + " by " + p.name()); }
        out.println("over-selection: " + beyond.size() + " boundary mutants, selected (test, mutant) pairs: " + overSelected + " (must be 0)");

        // 3. Per mutant: tests that reach it, full run, selected run, compare.
        int equal = 0, differ = 0, probeLabel = 0;
        long fullNs = 0, selNs = 0, fullRuns = 0, selRuns = 0;
        TreeMap<Integer, Integer> hist = new TreeMap<>();
        TreeMap<String, Integer> kindCount = new TreeMap<>();
        List<String> allBaseModels = new ArrayList<>();
        for (PerTest p : base) allBaseModels.addAll(p.models());
        for (Mut m : muts) {
            List<PerTest> sel = new ArrayList<>();
            for (PerTest p : base) if (reaches(p, m)) sel.add(p);
            if (m.rule() != 99) hist.merge(sel.size(), 1, Integer::sum);
            // full
            long a = System.nanoTime();
            String[] full = runSet(m, base, tests, allBaseModels, timeout);
            long fullT = System.nanoTime() - a;
            // selected
            long b = System.nanoTime();
            String[] chosen;
            List<String> selModels = new ArrayList<>();
            for (PerTest p : sel) selModels.addAll(p.models());
            if (sel.isEmpty()) chosen = new String[] {"survived", "no test reaches the rule"};
            else chosen = runSet(m, sel, tests, selModels, timeout);
            long selT = System.nanoTime() - b;
            fullNs += fullT; selNs += selT;
            fullRuns += base.size(); selRuns += sel.size();
            boolean same = kindOf(full).equals(kindOf(chosen)) && killer(full).equals(killer(chosen));
            kindCount.merge(full[0], 1, Integer::sum);
            if (same) equal++;
            // The probe: no solve has the rule; the full run compares models (equivalent), the selection runs nothing
            // (survived). Any other deviation (killed, invalid, error) is a real difference.
            else if (m.rule() == 99 && kindOf(full).equals("equivalent") && kindOf(chosen).equals("survived")) probeLabel++;
            else differ++;
            out.printf("  %-22s tests=%-2d full=%-10s selected=%-10s %s%n", m, sel.size(), kindOf(full), kindOf(chosen),
                same ? "same" : m.rule() == 99 && kindOf(full).equals("equivalent") && kindOf(chosen).equals("survived") ? "label differs, no test reaches it (full: " + full[1] + ")" : "DIFF full[" + full[1] + "] selected[" + chosen[1] + "]");
        }
        SolverHookRun.reset();
        int real = muts.size() - 1;
        double meanSel = (double) selRuns / real; // the probe selects no test
        out.printf("summary %s: %d tests, %d mutants run (+1 probe); tests per mutant: %s; mean %.2f of %d tests (%.1f %%)%n",
            fixture.getFileName(), base.size(), real, hist, meanSel, base.size(), 100 * meanSel / base.size());
        out.printf("summary %s: outcomes (full run, incl. probe) %s; same outcome with and without selection: %d, label differs for the unreached probe: %d, different: %d%n",
            fixture.getFileName(), kindCount, equal, probeLabel, differ);
        out.printf("summary %s: run time over the mutants: full %d ms, selected %d ms; test executions: full %d, selected %d%n",
            fixture.getFileName(), fullNs / 1_000_000, selNs / 1_000_000, fullRuns, selRuns);
        // Consistency: every generated mutant comes from a solve of some test, so only the probe may be unreached,
        // and the distribution covers every mutant run.
        int histSum = hist.values().stream().mapToInt(x -> x).sum();
        int unreachedReal = hist.getOrDefault(0, 0);
        boolean ok = differ == 0 && histSum == real && unreachedReal == 0 && overSelected == 0;
        out.println(ok ? "RESULT: OK" : "RESULT: FAIL " + differ + " different, distribution covers " + histSum + " of " + real
            + ", unreached " + unreachedReal + ", over-selected pairs " + overSelected);
        System.exit(ok ? 0 : 1);
    }

    /** True if one solve of the test has the rule (and for an atom mutant the body atom). */
    static boolean reaches(PerTest p, Mut m) {
        if (System.getProperty("spike.sabotage", "").equals("nomarks")) return false;
        if (System.getProperty("spike.sabotage", "").equals("reachall")) return true;
        for (String kinds : p.kinds()) {
            String[] rules = kinds.isEmpty() ? new String[0] : kinds.split(",", -1);
            if (m.rule() < rules.length && (m.op().equals("drop-rule") || m.atom() < rules[m.rule()].length())) return true;
        }
        return false;
    }

    static String kindOf(String[] c) { return c[0]; }

    /** The first failing test of a killed outcome (the detail starts with its name). */
    static String killer(String[] c) { return c[0].equals("killed") ? c[1].split(" ", 2)[0] : ""; }

    /** Runs the tests of the list in order with the mutant active and classifies like SolverHookRun. */
    static String[] runSet(Mut m, List<?> set, TreeMap<String, Runnable> tests, List<String> baseModels, long timeout) {
        SolverHookRun.reset();
        System.setProperty("flixw.op", String.valueOf(SolverHookRun.opCode(m.op())));
        System.setProperty("flixw.rule", String.valueOf(m.rule()));
        System.setProperty("flixw.atom", String.valueOf(m.atom()));
        TreeMap<String, Runnable> chosen = new TreeMap<>();
        for (Object o : set) {
            String name = o instanceof PerTest p ? p.name() : o.toString();
            chosen.put(name, tests.get(name));
        }
        List<FlixHost.TestResult> rs = FlixHost.runAll(chosen, timeout, List.of("FlixwMutate."));
        String[] c = SolverHookRun.classify(rs, baseModels, SolverHookRun.models());
        System.setProperty("flixw.op", "0");
        return c;
    }
}
