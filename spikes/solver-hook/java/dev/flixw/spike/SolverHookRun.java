package dev.flixw.spike;

import ca.uwaterloo.flix.api.Flix;
import ca.uwaterloo.flix.runtime.CompilationResult;
import ca.uwaterloo.flix.runtime.LoadedProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Spike engine for #1: one compile, one load, then per mutant a switch (JVM system properties read by the
 * Flix hook module) and a rerun of the program's tests. Prints one line per mutant and exits 1 if an outcome
 * differs from the expectation in the mutant list.
 */
public final class SolverHookRun {
    static final long TIMEOUT_MS = Long.getLong("spike.timeout", 60_000L);
    /** Argument 8 "nocheck" turns the hook's own stratification check off to show what Fixpoint3 does alone. */
    static boolean STRATCHECK = true;
    /** Fixture runs must reach the hook; the material run sets -Dspike.requireSolve=false (a file may have no solve). */
    static final boolean REQUIRE_SOLVE = !"false".equals(System.getProperty("spike.requireSolve"));

    record Mutant(String id, String op, int rule, int atom, String expected, String note) {}

    public static void main(String[] args) throws Exception {
        String route = args[0];
        Path fixture = Path.of(args[4]);
        Path mutantsFile = Path.of(args[5]);
        Path hook = Path.of(args[6]);
        STRATCHECK = !(args.length > 7 && args[7].equals("nocheck"));

        List<Path> files = new ArrayList<>();
        for (String d : new String[] {"src", "test"}) {
            if (!Files.isDirectory(fixture.resolve(d))) continue;
            try (Stream<Path> s = Files.walk(fixture.resolve(d))) {
                s.filter(p -> p.toString().endsWith(".flix")).sorted().forEach(files::add);
            }
        }
        if (route.equals("a")) files.add(hook); // route b carries the hook module inside the patched library file

        long t0 = System.nanoTime();
        CompilationResult cr = FlixHost.compile(FlixHost.newFlix(files, args.length > 8 ? List.of(Path.of(args[8])) : List.of()));
        long compileMs = ms(t0);
        if (route.equals("a") && scala.jdk.javaapi.CollectionConverters.asJava(cr.getClasses()).keySet().stream().noneMatch(d -> d.descriptorString().equals("LFixpoint3/Solver/Def$runSolverInternal;"))) {
            System.out.println("RESULT: NO-TARGET the compiled program has no Fixpoint3.Solver.runSolverInternal (it does not call the solver); nothing to hook");
            System.exit(REQUIRE_SOLVE ? 1 : 0);
        }
        scala.jdk.javaapi.CollectionConverters.asJava(cr.getClasses()).keySet().stream()
                .map(java.lang.constant.ClassDesc::descriptorString).filter(n -> n.contains("FlixwMutate") || n.contains("Solver/Def$runSolver"))
                .sorted().forEach(n -> System.out.println("  class " + n));
        long rewriteMs = 0;
        if (route.equals("a")) {
            long t1 = System.nanoTime();
            cr = RewriteSolver.rewrite(cr);
            rewriteMs = ms(t1);
        }
        long t2 = System.nanoTime();
        LoadedProgram program = FlixHost.load(cr);
        long loadMs = ms(t2);
        TreeMap<String, Runnable> tests = FlixHost.tests(program);
        if (tests.keySet().stream().allMatch(n -> n.startsWith("FlixwMutate.")) && FlixHost.main(program) != null) tests.put("main", FlixHost.main(program));
        System.out.println("route=" + route + " flix=" + System.getProperty("flixw.flixVersion", "see jar")
                + " compile_ms=" + compileMs + " rewrite_ms=" + rewriteMs + " load_ms=" + loadMs
                + " tests=" + tests.keySet());

        // Baseline: hook active, no mutation. This is the smoke test of the compatibility check.
        reset();
        List<FlixHost.TestResult> base = FlixHost.runAll(tests, TIMEOUT_MS, List.of("FlixwMutate."));
        List<String> baseModels = models();
        int solves = intProp("flixw.solves");
        for (int i = 0; i < intProp("flixw.others"); i++) System.out.println("  other " + System.getProperty("flixw.other." + i));
        for (var r : base) if (r.outcome() != FlixHost.Outcome.PASS) { System.out.println("  " + r.name() + " " + r.outcome() + " " + r.detail()); if (r.error() != null) r.error().printStackTrace(System.out); }
        System.out.println("baseline: " + summary(base) + " hookedSolves=" + solves + " models=" + baseModels.size());
        for (int i = 0; i < solves; i++) {
            System.out.println("  solve " + i + " " + System.getProperty("flixw.solve." + i + ".shape"));
        }
        for (int k = 0; k < solves; k++) {
            for (int i = 0; ; i++) {
                String r = System.getProperty("flixw.solve." + k + ".rule." + i);
                if (r == null) break;
                System.out.println("  solve " + k + " rule " + i + ": " + r);
            }
        }
        boolean ok = base.stream().allMatch(r -> r.outcome() == FlixHost.Outcome.PASS) && solves > 0;
        if (!ok) {
            boolean pass = base.stream().allMatch(r -> r.outcome() == FlixHost.Outcome.PASS);
            System.out.println(pass ? "RESULT: NO-SOLVE the program has no Datalog solve of its own (queries only); nothing to mutate"
                    : "RESULT: FAIL baseline tests fail");
            System.exit(pass && !REQUIRE_SOLVE ? 0 : 1);
        }

        // Smoke test of the design: dropping a rule must change at least one model.
        boolean auto = mutantsFile.getFileName().toString().equals("auto");
        List<Mutant> mutants = auto ? generate(solves, Integer.getInteger("spike.max", 300)) : read(mutantsFile);
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int mismatches = 0;
        for (Mutant m : mutants) {
            reset();
            System.setProperty("flixw.op", System.getProperty("spike.sabotage", "").equals("nomutate") ? "0" : String.valueOf(opCode(m.op())));
            System.setProperty("flixw.rule", String.valueOf(m.rule()));
            System.setProperty("flixw.atom", String.valueOf(m.atom()));
            long t = System.nanoTime();
            List<FlixHost.TestResult> rs = FlixHost.runAll(tests, TIMEOUT_MS, List.of("FlixwMutate."));
            long ms = ms(t);
            String[] cls = classify(rs, baseModels, models());
            String actual = cls[0];
            boolean match = auto || actual.equals(m.expected());
            counts.merge(actual, 1, Integer::sum);
            if (!match) mismatches++;
            System.out.printf("%-4s %-12s rule=%d atom=%d expected=%-10s actual=%-10s %s %dms  %s%n", m.id(), m.op(),
                    m.rule(), m.atom(), m.expected(), actual, match ? "ok  " : "DIFF", ms, cls[1]);
        }
        System.setProperty("flixw.op", "0");
        int leaked = FlixHost.awaitAbandoned(0);
        System.out.println("counts: " + counts + (leaked > 0 ? " leakedThreads=" + leaked : ""));
        System.out.println(mismatches == 0 ? "RESULT: OK " + mutants.size() + " mutants as expected"
                : "RESULT: FAIL " + mismatches + " mutants differ from the expectation");
        System.exit(mismatches == 0 ? 0 : 1);
    }

    /** killed: a test assertion failed; invalid: the solver rejected the program; equivalent/survived by model. */
    static String[] classify(List<FlixHost.TestResult> rs, List<String> baseModels, List<String> mutModels) {
        String bad = System.getProperty("flixw.invalid");
        if (bad != null) return new String[] {"invalid", "hook check: " + bad};
        for (FlixHost.TestResult r : rs) {
            if (r.outcome() == FlixHost.Outcome.ERROR) {
                boolean solver = r.error() != null && FlixHost.stackHas(r.error(), "Fixpoint3.");
                return new String[] {solver ? "invalid" : "error", r.name() + " " + r.detail()};
            }
        }
        for (FlixHost.TestResult r : rs) {
            if (r.outcome() == FlixHost.Outcome.FAIL) return new String[] {"killed", r.name() + " " + r.detail()};
        }
        for (FlixHost.TestResult r : rs) {
            if (r.outcome() == FlixHost.Outcome.TIMEOUT) return new String[] {"timeout", r.name()};
        }
        boolean same = baseModels.equals(mutModels) || "samemodel".equals(System.getProperty("spike.sabotage"));
        return new String[] {same ? "equivalent" : "survived", same ? "same model in all " + mutModels.size()
                + " solves" : "model differs, no test fails"};
    }

    /** Every (op, rule, atom) that exists in at least one baseline solve, thinned to at most max by stride. */
    static List<Mutant> generate(int solves, int max) {
        java.util.TreeSet<String> seen = new java.util.TreeSet<>();
        List<Mutant> all = new ArrayList<>();
        for (int k = 0; k < solves; k++) {
            String kinds = System.getProperty("flixw.solve." + k + ".kinds", "");
            String[] rules = kinds.isEmpty() ? new String[0] : kinds.split(",", -1);
            for (int r = 0; r < rules.length; r++) {
                if (seen.add("drop-rule " + r + " 0")) all.add(new Mutant("", "drop-rule", r, 0, "", ""));
                for (int a = 0; a < rules[r].length(); a++) {
                    if (seen.add("drop-atom " + r + " " + a)) all.add(new Mutant("", "drop-atom", r, a, "", ""));
                    if (rules[r].charAt(a) != '?' && seen.add("flip-negation " + r + " " + a))
                        all.add(new Mutant("", "flip-negation", r, a, "", ""));
                }
            }
        }
        int stride = Math.max(1, (all.size() + max - 1) / max);
        List<Mutant> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += stride) {
            Mutant m = all.get(i);
            out.add(new Mutant("a" + out.size(), m.op(), m.rule(), m.atom(), "", ""));
        }
        System.out.println("generated " + all.size() + " distinct (op, rule, atom), running " + out.size());
        return out;
    }

    static int opCode(String op) {
        return switch (op) {
            case "drop-rule" -> 1;
            case "drop-atom" -> 2;
            case "flip-negation" -> 3;
            default -> throw new IllegalArgumentException("unknown op " + op);
        };
    }

    static void reset() {
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("flixw.solve") || k.startsWith("flixw.model") || k.startsWith("flixw.other") || k.equals("flixw.invalid")) System.clearProperty(k);
        }
        System.setProperty("flixw.solves", "0");
        System.setProperty("flixw.others", "0");
        System.setProperty("flixw.models", "0");
        System.setProperty("flixw.op", "0");
        System.setProperty("flixw.stratcheck", STRATCHECK ? "1" : "0");
    }

    static List<String> models() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < intProp("flixw.models"); i++) out.add(System.getProperty("flixw.model." + i));
        return out;
    }

    static int intProp(String k) { return Integer.parseInt(System.getProperty(k, "0")); }

    static String summary(List<FlixHost.TestResult> rs) {
        long pass = rs.stream().filter(r -> r.outcome() == FlixHost.Outcome.PASS).count();
        return pass + "/" + rs.size() + " pass";
    }

    static List<Mutant> read(Path file) throws IOException {
        List<Mutant> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            out.add(new Mutant(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), f[4], f.length > 5 ? f[5] : ""));
        }
        return out;
    }

    static long ms(long since) { return (System.nanoTime() - since) / 1_000_000; }
}
