package dev.flixw.mutate;

import ca.uwaterloo.flix.runtime.CompilationResult;
import dev.flixw.spike.FlixHost;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Spike for flixw-mutate#22: test coverage of functional Flix from source markers. Sites come from the typed AST
 * ({@link CovSites}), the markers are inserted into the source text, the program is compiled once, every test runs
 * alone and the hit sets per test give lines, branches and functions. Optional ground truth: line annotations in
 * the sources (H, M, P) and two TSV files.
 *
 * <p>Usage: GenRun &lt;projectDir&gt; &lt;workDir&gt; &lt;support.jar&gt; &lt;timeoutMs&gt; &lt;reps&gt; [expect]
 * Properties: cov.lambdas=false (no lambda regions), cov.plain=false (skip the uninstrumented comparison run).
 */
public final class GenRun {
    static final java.io.PrintStream OUT = System.out;

    public static void main(String[] a) throws Exception {
        System.setOut(System.err);
        Thread t = new Thread(null, () -> {
            try {
                run(a);
            } catch (Throwable e) {
                e.printStackTrace();
                System.exit(2);
            }
        }, "gen", 1L << 29);
        t.start();
        t.join();
    }

    static void p(String s) { OUT.println(s); }

    static void run(String[] a) throws Exception {
        Path project = Path.of(a[0]).toAbsolutePath().normalize();
        Path work = Path.of(a[1]).toAbsolutePath();
        Path support = Path.of(a[2]);
        long timeout = Long.parseLong(a[3]);
        int reps = Integer.parseInt(a[4]);
        boolean expect = a.length > 5 && a[5].equals("expect");
        boolean lambdas = !"false".equals(System.getProperty("cov.lambdas"));
        boolean plain = !"false".equals(System.getProperty("cov.plain"));
        // cov.target=nop: the same instrumented program with a marker that does not record (overhead baseline; no model).
        boolean nop = "nop".equals(System.getProperty("cov.target"));
        // cov.sabotage=nomarks: the markers never record; outer: a line is owned by the outermost region. Both must fail.
        String sabotage = System.getProperty("cov.sabotage", "");
        if (nop || sabotage.equals("nomarks")) support = support.resolveSibling("nop.jar");
        Files.createDirectories(work);
        boolean[] hit = new boolean[1 << 16];
        System.getProperties().put("flixw.gen.hit", hit);

        List<Path> src = flix(project.resolve("src"));
        List<Path> tests = flix(project.resolve("test"));
        List<Path> all = new ArrayList<>(src);
        all.addAll(tests);
        Map<String, String> texts = new TreeMap<>();
        Map<String, String> rel = new TreeMap<>();
        for (Path f : src) {
            texts.put(f.toString(), Files.readString(f));
            rel.put(f.toString(), project.relativize(f).toString());
        }

        // 1. typed AST and sites
        long t0 = System.nanoTime();
        FlixApi api = new FlixApi(GenRun.class.getClassLoader());
        FlixApi.Checked checked = api.check(all);
        if (checked.failure() != null) throw new IllegalStateException(String.join("\n", checked.failure().messages()));
        long tCheck = ms(t0);
        t0 = System.nanoTime();
        CovSites sites = CovSites.find(api, checked.root(), texts, lambdas, tests.stream().map(Path::toString).collect(java.util.stream.Collectors.toSet()));
        long tFind = ms(t0);
        p("sites: " + sites.regions.size() + " regions (" + count(sites, CovSites.Kind.ENTRY) + " entry, " + count(sites, CovSites.Kind.THEN)
            + " then, " + count(sites, CovSites.Kind.ELSE) + " else, " + count(sites, CovSites.Kind.ARM) + " arm, " + count(sites, CovSites.Kind.LAMBDA)
            + " lambda), " + sites.execs.size() + " expression starts " + sites.nodeCounts + ", " + sites.skipped + " left out (unbalanced span)" + "; check " + tCheck + " ms, site walk " + tFind + " ms");

        StringBuilder rt = new StringBuilder("id\tkind\tfile\tline\tindex\tstart\tend\n");
        for (CovSites.Region r : sites.regions) rt.append(r.id()).append('\t').append(r.kind()).append('\t').append(rel.get(r.file())).append('\t').append(r.line()).append('\t').append(r.index()).append('\t').append(r.start()).append('\t').append(r.end()).append('\n');
        Files.writeString(work.resolve("regions.tsv"), rt.toString());
        if ("plain".equals(System.getProperty("cov.only"))) {
            // run-time baseline in a JVM of its own: the uninstrumented program, per-test times
            CompilationResult pcr = FlixHost.compile(FlixHost.newFlix(all, List.of()));
            TreeMap<String, Runnable> pfns = FlixHost.tests(FlixHost.load(pcr));
            TreeMap<String, List<Long>> pt = new TreeMap<>();
            List<Long> suite = new ArrayList<>();
            for (int rep = 0; rep < reps; rep++) {
                long s0 = System.nanoTime();
                for (var e : pfns.entrySet()) pt.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(FlixHost.runOne(e.getKey(), e.getValue(), timeout).nanos());
                suite.add(ms(s0));
            }
            if (pt.size() <= 30) for (var e : pt.entrySet()) p("time " + e.getKey() + ": plain " + stat(e.getValue()) + " (of " + reps + ")");
            p("tests: " + pfns.size() + ", plain suite median of " + reps + ": " + median(suite) + " ms, min " + java.util.Collections.min(suite) + " ms");
            return;
        }
        // 2. instrumented copies, same file names and line numbers
        Path inst = work.resolve("inst");
        Files.createDirectories(inst.resolve("src"));
        List<Path> instSrc = new ArrayList<>();
        t0 = System.nanoTime();
        for (Path f : src) {
            Path out = inst.resolve("src").resolve(project.resolve("src").relativize(f).toString().replace('/', '_'));
            Files.writeString(out, sites.render(f.toString()));
            instSrc.add(out);
        }
        Path mod = inst.resolve("FlixwCov.flix");
        Files.writeString(mod, CovSites.module(nop || sabotage.equals("nomarks") ? "dev.flixw.spike.CovNop" : "dev.flixw.spike.CovMark"));
        long tRender = ms(t0);
        List<Path> instAll = new ArrayList<>(instSrc);
        instAll.add(mod);
        instAll.addAll(tests);

        // 3. compile and load
        t0 = System.nanoTime();
        CompilationResult cr = FlixHost.compile(FlixHost.newFlix(instAll, List.of(support)));
        long tCompile = ms(t0);
        t0 = System.nanoTime();
        var loaded = FlixHost.load(cr);
        long tLoad = ms(t0);
        p("render " + tRender + " ms; instrumented compile " + tCompile + " ms, load " + tLoad + " ms");
        if (!nop && sabotage.isEmpty()) {
            MarkerAudit audit = MarkerAudit.scan(cr, sites, MarkerAudit.readAllow(project.resolve("marker-audit-allow.tsv")), rel);
            p(audit.summary());
            Files.writeString(work.resolve("marker-audit.tsv"), audit.tsv(rel));
            if (!audit.unexplained().isEmpty()) {
                for (MarkerAudit.Row m : audit.unexplained()) p("MISSING MARKER in a live function: " + m.region().kind() + " " + rel.get(m.region().file()) + ":" + m.region().line() + " in " + m.function());
                p("RESULT: FAIL " + audit.unexplained().size() + " missing markers in live functions");
                System.exit(1);
            }
            if (Boolean.getBoolean("cov.auditOnly")) return;
        }

        // 4. tests one by one
        TreeMap<String, Runnable> fns = FlixHost.tests(loaded);
        CovModel model = new CovModel(sites);
        TreeMap<String, String> outcome = new TreeMap<>();
        long base = 0;
        List<Long> suiteTimes = new ArrayList<>();
        TreeMap<String, List<Long>> instTimes = new TreeMap<>();
        for (int rep = 0; rep < reps; rep++) {
            long s = System.nanoTime();
            for (var e : fns.entrySet()) {
                Arrays.fill(hit, false);
                FlixHost.TestResult r = FlixHost.runOne(e.getKey(), e.getValue(), timeout);
                instTimes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r.nanos());
                if (rep == 0) {
                    BitSet b = new BitSet();
                    for (int i = 0; i < hit.length; i++) if (hit[i]) b.set(i);
                    model.perTest.put(e.getKey(), b);
                    outcome.put(e.getKey(), r.outcome().name());
                }
            }
            suiteTimes.add(ms(s));
        }
        base = median(suiteTimes);
        p("tests: " + fns.size() + ", outcomes " + count(outcome) + "; suite median of " + reps + ": " + base + " ms, min " + java.util.Collections.min(suiteTimes) + " ms (instrumented)");

        if (!plain && !nop && instTimes.size() <= 30) for (var e : instTimes.entrySet()) p("time " + e.getKey() + ": recording marker " + stat(e.getValue()) + " (of " + reps + ")");
        // 5. uninstrumented comparison: same outcomes, run time
        if (plain) {
            t0 = System.nanoTime();
            CompilationResult pcr = FlixHost.compile(FlixHost.newFlix(all, List.of()));
            long tPlainCompile = ms(t0);
            var ploaded = FlixHost.load(pcr);
            TreeMap<String, Runnable> pfns = FlixHost.tests(ploaded);
            TreeMap<String, String> pout = new TreeMap<>();
            TreeMap<String, List<Long>> plainTimes = new TreeMap<>();
            List<Long> pt = new ArrayList<>();
            for (int rep = 0; rep < reps; rep++) {
                long s = System.nanoTime();
                for (var e : pfns.entrySet()) {
                    FlixHost.TestResult r = FlixHost.runOne(e.getKey(), e.getValue(), timeout);
                    plainTimes.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(r.nanos());
                    if (rep == 0) pout.put(e.getKey(), r.outcome().name());
                }
                pt.add(ms(s));
            }
            p("plain compile " + tPlainCompile + " ms; plain suite median of " + reps + ": " + median(pt) + " ms; outcomes equal to instrumented: " + pout.equals(outcome));
            for (var e : plainTimes.entrySet()) {
                if (e.getValue().stream().mapToLong(x -> x).max().orElse(0) < 5_000_000 && instTimes.get(e.getKey()).stream().mapToLong(x -> x).max().orElse(0) < 5_000_000) continue;
                p("time " + e.getKey() + ": plain " + stat(e.getValue()) + "; instrumented " + stat(instTimes.get(e.getKey())) + " (of " + reps + ")");
            }
            if (!pout.equals(outcome)) {
                p("RESULT: FAIL instrumented outcomes differ from the plain run");
                System.exit(1);
            }
        }

        if (nop) {
            for (var e : instTimes.entrySet()) p("time " + e.getKey() + ": nop marker " + stat(e.getValue()) + " (of " + reps + ")");
            p("nop marker: model not built");
            return;
        }
        // 6. outputs
        BitSet allHits = model.all();
        Files.writeString(work.resolve("lcov.info"), CovFormats.lcov(model, allHits, "", rel));
        StringBuilder per = new StringBuilder();
        for (var e : model.perTest.entrySet()) per.append(CovFormats.lcov(model, e.getValue(), e.getKey(), rel));
        Files.writeString(work.resolve("lcov-per-test.info"), per.toString());
        Files.writeString(work.resolve("cobertura.xml"), CovFormats.cobertura(model, allHits, rel, project.toString()));
        String report = CovFormats.report(model, rel, base);
        Files.writeString(work.resolve("report.txt"), report);
        p(report);

        // 7. ground truth
        if (expect) {
            int bad = compare(project, model, texts, rel);
            p(bad == 0 ? "RESULT: OK" : "RESULT: FAIL " + bad + " deviations");
            if (bad != 0) System.exit(1);
        }
    }

    static String count(Map<String, String> m) {
        TreeMap<String, Integer> c = new TreeMap<>();
        for (String v : m.values()) c.merge(v, 1, Integer::sum);
        return c.toString();
    }

    static long count(CovSites s, CovSites.Kind k) { return s.regions.stream().filter(r -> r.kind() == k).count(); }

    static String stat(List<Long> l) {
        return "median " + medianUs(l) + " us, min " + java.util.Collections.min(l) / 1000 + " us";
    }

    static long medianUs(List<Long> l) { return median(l) / 1000; }

    static long ms(long t0) { return (System.nanoTime() - t0) / 1_000_000; }

    static long median(List<Long> l) {
        List<Long> s = new ArrayList<>(l);
        java.util.Collections.sort(s);
        return s.get(s.size() / 2);
    }

    static List<Path> flix(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(f -> f.toString().endsWith(".flix")).sorted().toList();
        }
    }

    // --- ground truth ----------------------------------------------------------------------------------------

    static int compare(Path project, CovModel m, Map<String, String> texts, Map<String, String> rel) throws IOException {
        int bad = 0, total = 0;
        BitSet all = m.all();
        for (String f : m.files()) {
            if (rel.get(f).endsWith("/Limits.flix")) continue; // compared with expected-limits.tsv below
            TreeMap<Integer, Character> got = m.lines(f, all);
            TreeMap<Integer, Character> want = new TreeMap<>();
            String[] ls = texts.get(f).split("\n", -1);
            for (int i = 0; i < ls.length; i++) {
                String l = ls[i].stripTrailing();
                int k = l.lastIndexOf("// ");
                if (k >= 0 && l.length() == k + 4 && "HMP".indexOf(l.charAt(k + 3)) >= 0) want.put(i + 1, l.charAt(k + 3));
            }
            java.util.TreeSet<Integer> keys = new java.util.TreeSet<>(got.keySet());
            keys.addAll(want.keySet());
            for (int line : keys) {
                total++;
                if (!java.util.Objects.equals(got.get(line), want.get(line))) {
                    bad++;
                    p("DEVIATION line " + rel.get(f) + ":" + line + " expected " + want.get(line) + " got " + got.get(line));
                }
            }
        }
        p("lines compared: " + total + ", deviating: " + bad);
        int nt = 0, nb = 0;
        for (String l : Files.readAllLines(project.resolve("expected-functions.tsv")).subList(1, Files.readAllLines(project.resolve("expected-functions.tsv")).size())) {
            String[] c = l.split("\t");
            nt++;
            Boolean g = null;
            for (String f : m.files()) if (rel.get(f).endsWith("/" + c[0])) for (CovSites.Region r : m.of(f, CovSites.Kind.ENTRY)) if (r.line() == Integer.parseInt(c[1])) g = all.get(r.id());
            if (g == null || g != c[2].equals("1")) { nb++; p("DEVIATION function " + c[0] + ":" + c[1] + " expected " + c[2] + " got " + g); }
        }
        p("functions compared: " + nt + ", deviating: " + nb);
        int bt = 0, bb = 0;
        List<String> bl = Files.readAllLines(project.resolve("expected-branches.tsv"));
        for (String l : bl.subList(1, bl.size())) {
            String[] c = l.split("\t");
            bt++;
            Boolean g = null;
            for (String f : m.files()) if (rel.get(f).endsWith("/" + c[0]))
                for (CovSites.Region r : m.of(f, CovSites.Kind.THEN, CovSites.Kind.ELSE, CovSites.Kind.ARM))
                    if (r.line() == Integer.parseInt(c[1]) && r.index() == Integer.parseInt(c[3])
                        && (c[2].equals("if") == (r.kind() != CovSites.Kind.ARM))) g = all.get(r.id());
            if (g == null || g != c[4].equals("1")) { bb++; p("DEVIATION branch " + c[0] + ":" + c[1] + " " + c[2] + " " + c[3] + " expected " + c[4] + " got " + g); }
        }
        p("branches compared: " + bt + ", deviating: " + bb);
        // the written LCOV and Cobertura carry the annotated truth: H and P are hit lines, M is not
        int fb = 0, ft = 0;
        String lcov = CovFormats.lcov(m, all, "", rel);
        String cob = CovFormats.cobertura(m, all, rel, "");
        for (String f : m.files()) {
            if (rel.get(f).endsWith("/Limits.flix")) continue;
            String[] ls = texts.get(f).split("\n", -1);
            String rec = lcov.substring(lcov.indexOf("SF:" + rel.get(f) + "\n"));
            rec = rec.substring(0, rec.indexOf("end_of_record"));
            String cl = cob.substring(cob.indexOf("filename=\"" + rel.get(f) + "\""));
            cl = cl.substring(0, cl.indexOf("</class>"));
            for (int i = 0; i < ls.length; i++) {
                String t = ls[i].stripTrailing();
                int k = t.lastIndexOf("// ");
                if (k < 0 || t.length() != k + 4 || "HMP".indexOf(t.charAt(k + 3)) < 0) continue;
                int hit = t.charAt(k + 3) == 'M' ? 0 : 1;
                ft++;
                boolean okL = rec.contains("\nDA:" + (i + 1) + "," + hit + "\n");
                boolean okC = cl.contains("<line number=\"" + (i + 1) + "\" hits=\"" + hit + "\"");
                if (!okL || !okC) { fb++; p("DEVIATION format " + rel.get(f) + ":" + (i + 1) + " truth " + t.charAt(k + 3) + " lcov " + okL + " cobertura " + okC); }
            }
        }
        p("format lines compared: " + ft + ", deviating: " + fb);
        // known limits: lines where the tool is expected to differ from the truth, as listed
        int lt = 0, lb = 0;
        java.nio.file.Path limits = project.resolve("expected-limits.tsv");
        if (Files.exists(limits)) {
            for (String l : Files.readAllLines(limits).subList(1, Files.readAllLines(limits).size())) {
                String[] c = l.split("\t");
                lt++;
                Character g = null;
                for (String f : m.files()) if (rel.get(f).endsWith("/" + c[0])) g = m.lines(f, all).get(Integer.parseInt(c[1]));
                if (g == null || g != c[3].charAt(0) || c[2].equals(c[3])) {
                    lb++;
                    p("DEVIATION limit " + c[0] + ":" + c[1] + " truth " + c[2] + ", tool expected " + c[3] + ", got " + g);
                }
            }
            p("known limits listed: " + lt + ", tool result differs from the listing: " + lb);
        }
        return bad + nb + bb + lb + fb + compareTable(project, m, texts, rel);
    }

    /**
     * The same truth read from the site table: the model becomes a SiteTable and Hits, and the views of the table
     * (not the model) must give the annotated lines, expected-functions.tsv and expected-branches.tsv.
     */
    static int compareTable(Path project, CovModel m, Map<String, String> texts, Map<String, String> rel) throws IOException {
        CovModel.Tabled t = m.tabled(rel);
        BitSet all = t.hits().union();
        List<String> wantLines = new ArrayList<>();
        for (String f : m.files()) {
            if (rel.get(f).endsWith("/Limits.flix")) continue;
            String[] ls = texts.get(f).split("\n", -1);
            for (int i = 0; i < ls.length; i++) {
                String l = ls[i].stripTrailing();
                int k = l.lastIndexOf("// ");
                if (k >= 0 && l.length() == k + 4 && "HMP".indexOf(l.charAt(k + 3)) >= 0)
                    wantLines.add(Path.of(rel.get(f)).getFileName() + ":" + (i + 1) + ":" + l.charAt(k + 3));
            }
        }
        List<String> gotLines = new ArrayList<>();
        for (String r : Views.lines(t.sites(), all)) if (!r.startsWith("Limits.flix:")) gotLines.add(r);
        List<String> wantFn = new ArrayList<>(Files.readAllLines(project.resolve("expected-functions.tsv")).stream().skip(1).toList());
        List<String> wantBr = new ArrayList<>(Files.readAllLines(project.resolve("expected-branches.tsv")).stream().skip(1).toList());
        int bad = 0;
        bad += diff("table lines", wantLines, gotLines);
        // Limits.flix is outside expected-functions.tsv and expected-branches.tsv (its lines go to expected-limits.tsv)
        bad += diff("table functions", wantFn, Views.functions(t.sites(), all).stream().filter(r -> !r.startsWith("Limits.flix\t")).toList());
        bad += diff("table branches", wantBr, Views.branches(t.sites(), all).stream().filter(r -> !r.startsWith("Limits.flix\t")).toList());
        return bad;
    }

    private static int diff(String what, List<String> want, List<String> got) {
        java.util.TreeSet<String> w = new java.util.TreeSet<>(want), g = new java.util.TreeSet<>(got);
        int bad = 0;
        for (String x : w) if (!g.contains(x)) { bad++; p("DEVIATION " + what + " missing " + x); }
        for (String x : g) if (!w.contains(x)) { bad++; p("DEVIATION " + what + " unexpected " + x); }
        p(what + " from the site table compared: " + w.size() + ", deviating: " + bad);
        return bad;
    }
}
