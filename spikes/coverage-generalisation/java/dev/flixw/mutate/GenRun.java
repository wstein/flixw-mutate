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
        CovSites sites = CovSites.find(api, checked.root(), texts, lambdas);
        long tFind = ms(t0);
        p("sites: " + sites.regions.size() + " regions (" + count(sites, CovSites.Kind.ENTRY) + " entry, " + count(sites, CovSites.Kind.THEN)
            + " then, " + count(sites, CovSites.Kind.ELSE) + " else, " + count(sites, CovSites.Kind.ARM) + " arm, " + count(sites, CovSites.Kind.LAMBDA)
            + " lambda), " + sites.execs.size() + " expression starts " + sites.nodeCounts + "; check " + tCheck + " ms, site walk " + tFind + " ms");

        StringBuilder rt = new StringBuilder("id\tkind\tfile\tline\tindex\tstart\tend\n");
        for (CovSites.Region r : sites.regions) rt.append(r.id()).append('\t').append(r.kind()).append('\t').append(rel.get(r.file())).append('\t').append(r.line()).append('\t').append(r.index()).append('\t').append(r.start()).append('\t').append(r.end()).append('\n');
        Files.writeString(work.resolve("regions.tsv"), rt.toString());
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
        if (!nop && sabotage.isEmpty()) audit(cr, sites);

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
        p("tests: " + fns.size() + ", outcomes " + count(outcome) + "; suite median of " + reps + ": " + base + " ms (instrumented)");

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
                p("time " + e.getKey() + ": plain " + medianUs(e.getValue()) + " us, instrumented " + medianUs(instTimes.get(e.getKey())) + " us (median of " + reps + ")");
            }
            if (!pout.equals(outcome)) {
                p("RESULT: FAIL instrumented outcomes differ from the plain run");
                System.exit(1);
            }
        }

        if (nop) {
            for (var e : instTimes.entrySet()) p("time " + e.getKey() + ": nop marker " + medianUs(e.getValue()) + " us (median of " + reps + ")");
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

    /** Markers in the compiled bytecode: distinct ids and call sites of CovMark.hit, against the inserted regions. */
    static void audit(CompilationResult cr, CovSites sites) {
        TreeMap<Integer, Integer> found = new TreeMap<>();
        scala.collection.immutable.Map<java.lang.constant.ClassDesc, ca.uwaterloo.flix.language.phase.jvm.JvmClass> classes = cr.root().classes();
        scala.jdk.javaapi.CollectionConverters.asJava(classes).forEach((cd, jc) -> new ClassReader(jc.bytecode()).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    int last = -1;

                    @Override public void visitInsn(int op) { last = op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5 ? op - Opcodes.ICONST_0 : -1; }

                    @Override public void visitIntInsn(int op, int v) { last = op == Opcodes.BIPUSH || op == Opcodes.SIPUSH ? v : -1; }

                    @Override public void visitLdcInsn(Object v) { last = v instanceof Integer i ? i : -1; }

                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (owner.equals("dev/flixw/spike/CovMark") && n.equals("hit")) found.merge(last, 1, Integer::sum);
                        last = -1;
                    }
                };
            }
        }, 0));
        int dup = 0, sitesTotal = 0, missing = 0, unknown = 0;
        for (var e : found.entrySet()) { sitesTotal += e.getValue(); if (e.getValue() > 1) dup++; }
        List<Integer> miss = new ArrayList<>();
        for (CovSites.Region r : sites.regions) if (!found.containsKey(r.id())) { missing++; miss.add(r.id()); }
        for (int id : found.keySet()) if (id < 1 || id > sites.regions.size()) unknown++;
        p("marker audit (bytecode): inserted " + sites.regions.size() + ", distinct ids found " + found.size() + ", call sites " + sitesTotal
            + ", ids at more than one call site " + dup + ", inserted but missing " + missing + (missing > 0 && missing < 20 ? " " + miss : "")
            + ", ids not inserted " + unknown);
    }

    // --- ground truth ----------------------------------------------------------------------------------------

    static int compare(Path project, CovModel m, Map<String, String> texts, Map<String, String> rel) throws IOException {
        int bad = 0, total = 0;
        BitSet all = m.all();
        for (String f : m.files()) {
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
        return bad + nb + bb;
    }
}
