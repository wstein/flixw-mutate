package dev.flixw.mutate;

import ca.uwaterloo.flix.runtime.CompilationResult;
import dev.flixw.spike.FlixHost;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Spike for flixw-mutate#22, Datalog part: rules and facts per test through the solver hook ({@code datalog/CovHook.flix},
 * route b). Runs every test alone, reads the properties the hook writes, maps a rule of the constraint set back to its source
 * line through the typed AST of the fixpoint constraint sets (by head and body predicate names), prints a table per test
 * and, with ground truth files, compares it.
 *
 * <p>Usage (through GenLauncher): DatalogGen &lt;projectDir&gt; &lt;timeoutMs&gt; [expect]
 */
public final class DatalogGen {
    record Rule(String text, String sig, long derived) {}

    record Solve(List<Rule> rules, List<String> facts, long edb, String rel) {}

    /** Source file of a rule signature, and the lines of the facts per head predicate (typed AST), for the LCOV output. */
    static final TreeMap<String, String> SIG_FILE = new TreeMap<>();
    static final TreeMap<String, List<String>> FACT_LINES = new TreeMap<>();

    static final Pattern PRED = Pattern.compile("(?<![A-Za-z0-9_$])([A-Z][A-Za-z0-9_]*)\\(");

    public static void main(String[] a) throws Exception {
        java.io.PrintStream out = System.out;
        System.setOut(System.err);
        Thread t = new Thread(null, () -> {
            try {
                run(a, out);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(2);
            }
        }, "dl", 1L << 28);
        t.start();
        t.join();
    }

    static List<Path> flix(Path dir) throws java.io.IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> f.toString().endsWith(".flix")).sorted().toList();
        }
    }

    static String sigOfText(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = PRED.matcher(text);
        while (m.find()) names.add(m.group(1));
        if (names.isEmpty()) return "?";
        return names.get(0) + "<-" + String.join(",", names.subList(1, names.size()));
    }

    public static void run(String[] a, java.io.PrintStream out) throws Exception {
        Path project = Path.of(a[0]).toAbsolutePath().normalize();
        long timeout = Long.parseLong(a[1]);
        boolean expect = a.length > 2 && a[2].equals("expect");
        List<Path> src = flix(project.resolve("src"));
        List<Path> all = new ArrayList<>(src);
        all.addAll(flix(project.resolve("test")));
        if (src.isEmpty()) src = all; // a single test file: its constraint sets are the sources
        boolean quiet = Boolean.getBoolean("dl.quiet");
        int reps = Integer.getInteger("dl.reps", 1);
        boolean hooked = !Boolean.getBoolean("dl.stock");

        // source rules: signature to lines, from the typed AST
        FlixApi api = new FlixApi(DatalogGen.class.getClassLoader());
        FlixApi.Checked checked = api.check(all);
        if (checked.failure() != null) throw new IllegalStateException(String.join("\n", checked.failure().messages()));
        TreeMap<String, List<Integer>> sourceRules = new TreeMap<>();
        int[] constraints = {0};
        collect(api, checked.root(), src, sourceRules, constraints, new java.util.IdentityHashMap<>());
        // defs: simple name of the def symbol to its lines (the test that contains a constraint set narrows the candidates)
        TreeMap<String, int[]> defLines = new TreeMap<>();
        collectDefs(api, checked.root(), defLines, new java.util.IdentityHashMap<>());
        if (!quiet) out.println("source: " + constraints[0] + " constraints (rules and facts) in fixpoint constraint sets; rule signatures: " + sourceRules);

        long t0 = System.nanoTime();
        CompilationResult cr = FlixHost.compile(FlixHost.newFlix(all, List.of()));
        var loaded = FlixHost.load(cr);
        out.println("compile+load with the hook: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        TreeMap<String, Runnable> tests = FlixHost.tests(loaded);
        TreeMap<String, List<Solve>> perTest = new TreeMap<>();
        for (var e : tests.entrySet()) {
            if (e.getKey().startsWith("Fixpoint3.") || e.getKey().startsWith("FlixwCov")) continue;
            reset();
            long s = System.nanoTime();
            FlixHost.TestResult r = FlixHost.runOne(e.getKey(), e.getValue(), timeout);
            long ms = (System.nanoTime() - s) / 1_000_000;
            List<Solve> solves = read();
            perTest.put(e.getKey(), solves);
            if (!quiet || r.outcome() != FlixHost.Outcome.PASS) out.println("test " + e.getKey() + ": " + r.outcome() + ", " + solves.size() + " solve(s), " + ms + " ms");
            int n = 0;
            for (Solve sv : quiet ? List.<Solve>of() : solves) {
                out.println("  solve " + n++ + ": " + sv.facts().size() + " facts, " + sv.rules().size() + " rules, tuples first derived from facts: "
                    + sv.edb() + ", relations " + sv.rel());
                for (Rule ru : sv.rules()) {
                    List<Integer> lines = lines(sourceRules, defLines, e.getKey(), ru.sig());
                    out.println("    " + (ru.derived() > 0 ? "fired    " : ru.derived() == 0 ? "not fired" : "unknown  ") + " derived=" + (ru.derived() < 0 ? "n/a" : String.valueOf(ru.derived())) + "  " + ru.sig()
                        + "  source line " + (lines == null ? "?" : lines.size() == 1 ? lines.get(0) : "ambiguous " + lines) + "   " + ru.text());
                }
                for (String f : sv.facts()) out.println("    fact " + f);
                java.util.TreeSet<String> read = new java.util.TreeSet<>(), unread = new java.util.TreeSet<>();
                for (Rule ru : sv.rules()) {
                    String body = ru.sig().substring(ru.sig().indexOf("<-") + 2);
                    if (!body.isEmpty()) read.addAll(List.of(body.split(",")));
                }
                for (String f : sv.facts()) if (!read.contains(f.substring(0, f.indexOf('(')))) unread.add(f.substring(0, f.indexOf('(')));
                if (!unread.isEmpty()) out.println("    facts of predicates that no rule of this solve reads: " + unread);
            }
        }
        // timing: the suite of all tests, reps times, with the hook (or the stock library with -Ddl.stock=true)
        List<Long> suite = new ArrayList<>();
        for (int rep = 0; rep < reps; rep++) {
            long s = System.nanoTime();
            for (var e : tests.entrySet()) {
                if (e.getKey().startsWith("Fixpoint3.") || e.getKey().startsWith("FlixwCov")) continue;
                reset();
                FlixHost.runOne(e.getKey(), e.getValue(), timeout);
            }
            suite.add((System.nanoTime() - s) / 1_000_000);
        }
        java.util.Collections.sort(suite);
        // summary over all tests: rules by signature, fired in at least one test, solves, mapping to source lines
        java.util.TreeSet<String> sigs = new java.util.TreeSet<>(), fired = new java.util.TreeSet<>(), known = new java.util.TreeSet<>();
        int solvesTotal = 0, withSolve = 0, rulesTotal = 0;
        long facts = 0;
        for (var e : perTest.entrySet()) {
            if (!e.getValue().isEmpty()) withSolve++;
            for (Solve sv : e.getValue()) {
                solvesTotal++;
                facts += sv.facts().size();
                for (Rule ru : sv.rules()) {
                    rulesTotal++;
                    sigs.add(ru.sig());
                    if (ru.derived() > 0) fired.add(ru.sig());
                    if (ru.derived() >= 0) known.add(ru.sig());
                }
            }
        }
        int unique = 0, ambiguous = 0, unknown = 0, uniqueAll = 0;
        for (var e : perTest.entrySet()) for (Solve sv : e.getValue()) for (Rule ru : sv.rules()) {
            List<Integer> l = lines(sourceRules, defLines, e.getKey(), ru.sig());
            if (l == null) unknown++; else if (l.size() == 1) unique++; else ambiguous++;
            List<Integer> g = sourceRules.get(ru.sig());
            if (g != null && g.size() == 1) uniqueAll++;
        }
        out.println("summary: " + perTest.size() + " tests, " + withSolve + " with a solve, " + solvesTotal + " solves, " + rulesTotal + " rule instances, " + facts
            + " facts; " + sigs.size() + " distinct rule signatures, fired in some test " + fired.size() + ", never fired " + (known.size() - fired.size()) + ", no provenance " + (sigs.size() - known.size()) + "; "
            + "source line of a rule instance, by signature alone: unique " + uniqueAll + "; narrowed to the test's def: unique " + unique + ", ambiguous " + ambiguous + ", none " + unknown
            + "; suite " + (hooked ? "with the hook" : "stock library") + " median of " + reps + ": " + suite.get(suite.size() / 2) + " ms (min " + suite.get(0) + ")");
        String lcovPath = System.getProperty("dl.lcov");
        if (lcovPath != null) {
            Files.createDirectories(Path.of(lcovPath).getParent());
            Files.writeString(Path.of(lcovPath), lcov(project, perTest, sourceRules, defLines));
            out.println("wrote " + lcovPath);
        }
        if (expect) {
            int bad = 0, total = 0;
            for (String l : Files.readAllLines(project.resolve("expected-rules.tsv")).stream().skip(1).toList()) {
                String[] c = l.split("\t");
                total++;
                Rule found = null;
                for (Solve sv : perTest.getOrDefault(c[0], List.of())) for (Rule ru : sv.rules()) if (ru.sig().equals(c[1])) found = ru;
                List<Integer> lines = sourceRules.get(c[1]);
                boolean ok = found != null && found.derived() == Long.parseLong(c[2]) && lines != null && lines.size() == 1 && lines.get(0) == Integer.parseInt(c[3]);
                if (!ok) { bad++; out.println("DEVIATION rule " + c[0] + " " + c[1] + " expected derived " + c[2] + " line " + c[3] + ", got " + (found == null ? "none" : found.derived()) + " line " + lines); }
            }
            for (String l : Files.readAllLines(project.resolve("expected-facts.tsv")).stream().skip(1).toList()) {
                String[] c = l.split("\t");
                total++;
                List<Solve> sv = perTest.getOrDefault(c[0], List.of());
                int got = sv.size() > Integer.parseInt(c[1]) ? sv.get(Integer.parseInt(c[1])).facts().size() : -1;
                if (got != Integer.parseInt(c[2])) { bad++; out.println("DEVIATION facts " + c[0] + " solve " + c[1] + " expected " + c[2] + " got " + got); }
            }
            // solves per test, relation sizes and tuples from facts per solve
            Path er = project.resolve("expected-relations.tsv");
            for (String l : Files.readAllLines(er).stream().skip(1).toList()) {
                String[] c = l.split("\t");
                total++;
                List<Solve> sv = perTest.getOrDefault(c[0], List.of());
                int want = Integer.parseInt(c[1]);
                String got = "none";
                boolean ok = sv.size() == want;
                if (ok && want > 0) {
                    Solve s0 = sv.get(0);
                    java.util.TreeSet<String> rel = new java.util.TreeSet<>(List.of(s0.rel().split(",")));
                    java.util.TreeSet<String> exp = new java.util.TreeSet<>(List.of(c[2].split(",")));
                    got = s0.rel() + " edb " + s0.edb();
                    ok = rel.equals(exp) && (s0.edb() < 0 || s0.edb() == Long.parseLong(c[3]));
                }
                if (!ok) { bad++; out.println("DEVIATION relations " + c[0] + " expected solves " + c[1] + " " + c[2] + " edb " + c[3] + ", got " + sv.size() + " " + got); }
            }
            long noSolve = perTest.values().stream().filter(List::isEmpty).count();
            total++;
            if (noSolve != 1) { bad++; out.println("DEVIATION tests without a solve expected 1 got " + noSolve); }
            out.println("compared: " + total + ", deviating: " + bad);
            out.println(bad == 0 ? "RESULT: OK" : "RESULT: FAIL " + bad + " deviations");
            System.exit(bad == 0 ? 0 : 1);
        }
    }

    /**
     * LCOV for the Datalog part: a function per rule (FN: line and signature; FNDA: tuples derived first, summed over the tests,
     * 1 if the run had no provenance), a line per rule (hit if it fired) and per fact (hit if a solve of some test had a fact of
     * that predicate: presence, not use).
     */
    static String lcov(Path project, TreeMap<String, List<Solve>> perTest, TreeMap<String, List<Integer>> sourceRules, TreeMap<String, int[]> defLines) {
        TreeMap<String, TreeMap<Integer, long[]>> fn = new TreeMap<>(); // file to line to {derived, present}
        TreeMap<String, TreeMap<Integer, String>> names = new TreeMap<>();
        TreeMap<String, TreeMap<Integer, Boolean>> da = new TreeMap<>();
        java.util.TreeSet<String> factPreds = new java.util.TreeSet<>();
        for (var sig : sourceRules.entrySet()) {
            if (sig.getKey().startsWith("$Result")) continue; // a query, not a rule of the program
            String file = SIG_FILE.get(sig.getKey());
            for (int line : sig.getValue()) {
                fn.computeIfAbsent(file, k -> new TreeMap<>()).put(line, new long[] {0, 0});
                names.computeIfAbsent(file, k -> new TreeMap<>()).put(line, sig.getKey());
                da.computeIfAbsent(file, k -> new TreeMap<>()).put(line, false);
            }
        }
        for (var e : perTest.entrySet()) for (Solve sv : e.getValue()) {
            for (String f : sv.facts()) factPreds.add(f.substring(0, f.indexOf('(')));
            for (Rule ru : sv.rules()) {
                List<Integer> lines = lines(sourceRules, defLines, e.getKey(), ru.sig());
                if (lines == null || lines.size() != 1 || ru.sig().startsWith("$Result")) continue;
                String file = SIG_FILE.get(ru.sig());
                long[] c = fn.get(file).get(lines.get(0));
                c[0] += Math.max(ru.derived(), 0);
                if (ru.derived() < 0) c[1] = 2;
                if (ru.derived() != 0) da.get(file).put(lines.get(0), true);
            }
        }
        for (var fp : FACT_LINES.entrySet()) for (String fl : fp.getValue()) {
            String[] c = fl.split("\t");
            da.computeIfAbsent(c[0], k -> new TreeMap<>()).merge(Integer.parseInt(c[1]), factPreds.contains(fp.getKey()), (x, y) -> x || y);
        }
        StringBuilder sb = new StringBuilder();
        for (String file : da.keySet()) {
            sb.append("TN:\nSF:").append(project.relativize(Path.of(file))).append("\n");
            int fnh = 0;
            TreeMap<Integer, long[]> fns = fn.getOrDefault(file, new TreeMap<>());
            for (var e : fns.entrySet()) sb.append("FN:").append(e.getKey()).append(",").append(names.get(file).get(e.getKey())).append("\n");
            for (var e : fns.entrySet()) {
                long count = e.getValue()[0] > 0 ? e.getValue()[0] : e.getValue()[1] == 2 ? 1 : 0; // 2: a run without provenance, presence only
                sb.append("FNDA:").append(count).append(",").append(names.get(file).get(e.getKey())).append("\n");
                if (count > 0) fnh++;
            }
            sb.append("FNF:").append(fns.size()).append("\nFNH:").append(fnh).append("\nBRF:0\nBRH:0\n");
            int lh = 0;
            for (var e : da.get(file).entrySet()) {
                sb.append("DA:").append(e.getKey()).append(",").append(e.getValue() ? 1 : 0).append("\n");
                if (e.getValue()) lh++;
            }
            sb.append("LF:").append(da.get(file).size()).append("\nLH:").append(lh).append("\nend_of_record\n");
        }
        return sb.toString();
    }

    /** The source lines of a rule signature; inside the def of the test, if it has constraint sets itself. */
    static List<Integer> lines(TreeMap<String, List<Integer>> sourceRules, TreeMap<String, int[]> defLines, String test, String sig) {
        List<Integer> all = sourceRules.get(sig);
        int[] span = defLines.get(test);
        if (all == null || span == null) return all;
        List<Integer> in = new ArrayList<>();
        for (int l : all) if (l >= span[0] && l <= span[1]) in.add(l);
        return in.isEmpty() ? all : in;
    }

    static void collectDefs(FlixApi api, Object node, TreeMap<String, int[]> out, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) return;
        String name = FlixApi.nodeName(node);
        if (name.equals("TypedAst$Def")) {
            Object loc = FlixApi.call(node, "loc");
            out.put(String.valueOf(FlixApi.call(node, "sym")), new int[] {(Integer) FlixApi.call(loc, "startLine"), (Integer) FlixApi.call(loc, "endLine")});
        }
        if (name.equals("TypedAst$Expr$Fixpoint") || name.startsWith("TypedAst$Expr$Fixpoint")) return;
        if (name.endsWith("ListMap")) { collectDefs(api, FlixApi.call(node, "m"), out, seen); return; }
        for (Object c : api.children(node)) collectDefs(api, c, out, seen);
    }

    static void reset() {
        System.getProperties().keySet().removeIf(k -> k.toString().startsWith("flixw.cov."));
    }

    static List<Solve> read() {
        int n = Integer.parseInt(System.getProperty("flixw.cov.solves", "0"));
        List<Solve> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int nr = Integer.parseInt(System.getProperty("flixw.cov." + i + ".rules", "0"));
            int nf = Integer.parseInt(System.getProperty("flixw.cov." + i + ".facts", "0"));
            String[] d = System.getProperty("flixw.cov." + i + ".derived", "").split(",");
            List<Rule> rules = new ArrayList<>();
            for (int r = 0; r < nr; r++) {
                String text = System.getProperty("flixw.cov." + i + ".rule." + r, "");
                rules.add(new Rule(text, System.getProperty("flixw.cov." + i + ".sig." + r, "?"), d.length > r + 1 && !d[r + 1].isEmpty() ? Long.parseLong(d[r + 1]) : -1));
            }
            List<String> facts = new ArrayList<>();
            for (int f = 0; f < nf; f++) facts.add(System.getProperty("flixw.cov." + i + ".fact." + f, ""));
            out.add(new Solve(rules, facts, d[0].isEmpty() ? -1 : Long.parseLong(d[0]), System.getProperty("flixw.cov." + i + ".rel", "")));
        }
        return out;
    }

    static void collect(FlixApi api, Object node, List<Path> src, TreeMap<String, List<Integer>> out, int[] count, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) return;
        String name = FlixApi.nodeName(node);
        if (name.equals("TypedAst$Constraint")) {
            Object loc = FlixApi.call(node, "loc");
            Object source = FlixApi.call(loc, "source");
            String file = String.valueOf(FlixApi.call(source, "sourceName"));
            if (src.stream().anyMatch(p -> p.toString().equals(file))) {
                count[0]++;
                List<Object> body = api.children(FlixApi.call(node, "body"));
                List<String> names = new ArrayList<>();
                for (Object b : body) if (FlixApi.nodeName(b).equals("TypedAst$Predicate$Body$Atom")) names.add(String.valueOf(FlixApi.call(FlixApi.call(b, "pred"), "name")));
                String head = String.valueOf(FlixApi.call(FlixApi.call(FlixApi.call(node, "head"), "pred"), "name"));
                if (!body.isEmpty()) {
                    String sig = head + "<-" + String.join(",", names);
                    out.computeIfAbsent(sig, k -> new ArrayList<>()).add((Integer) FlixApi.call(loc, "startLine"));
                    SIG_FILE.put(sig, file);
                } else {
                    FACT_LINES.computeIfAbsent(head, k -> new ArrayList<>()).add(file + "\t" + FlixApi.call(loc, "startLine"));
                }
            }
        }
        for (Object c : api.children(node)) collect(api, c, src, out, count, seen);
    }
}
