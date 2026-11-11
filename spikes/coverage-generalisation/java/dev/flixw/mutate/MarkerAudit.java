package dev.flixw.mutate;

import ca.uwaterloo.flix.runtime.CompilationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Audit of the coverage markers in the compiled bytecode (flixw-mutate#29). A marker that is inserted into the source but
 * absent from the bytecode can only read "not hit"; in a live function that would be a false "not covered". Every
 * missing marker gets one class: {@code DEAD_ENTRY} (the entry marker of a dead function), {@code IN_DEAD} (any other marker
 * of one), {@code ALLOWED} (a {@code LIVE} marker that the project's allow list names, with a reason) or {@code LIVE}.
 * A def is dropped when no class of the bytecode carries its namespace and name ({@code Def$name}, {@code Def$name$id},
 * {@code Clo$name$id}). A def is live when it is in the bytecode, is in a test file, or is reached through the call graph
 * (applications of defs, {@code ApplyDef}, in the checked and test files) from a def that is; a dropped def that is live
 * was inlined, so its body is in a caller. Every other def is dead. A name followed by {@code $digits} may be a lifted lambda
 * or an overload and counts as present: the audit errs towards {@code LIVE}. Every {@code LIVE} row fails the audit.
 */
final class MarkerAudit {
    enum Class { DEAD_ENTRY, IN_DEAD, ALLOWED, LIVE }

    record Row(CovSites.Region region, Class cls, String function, String cause) {}

    /** Number of call sites per marker id found in the bytecode. */
    final TreeMap<Integer, Integer> found;
    final List<Row> missing = new ArrayList<>();
    final int inserted;
    /** Ids in the bytecode that no region has (a harness fault). */
    final int unknown;
    private final Set<String> classNames;
    private final CovSites sites;
    private final Set<String> live = new TreeSet<>();

    /** {@code rel}: source name to project-relative path. {@code allow}: "file|KIND|line" of a LIVE marker to the reason it is accepted (see {@link #readAllow}). */
    MarkerAudit(Map<Integer, Integer> found, Set<String> classNames, CovSites sites, Map<String, String> allow, Map<String, String> rel) {
        this.found = new TreeMap<>(found);
        this.classNames = classNames;
        this.sites = sites;
        this.inserted = sites.regions.size();
        java.util.ArrayDeque<String> work = new java.util.ArrayDeque<>();
        for (String d : sites.defs) if (present(d) && live.add(d)) work.add(d);
        for (String d : sites.roots) if (live.add(d)) work.add(d);
        while (!work.isEmpty())
            for (String callee : sites.calls.getOrDefault(work.poll(), Set.of())) if (live.add(callee)) work.add(callee);
        for (CovSites.Region r : sites.regions) {
            if (found.containsKey(r.id())) continue;
            boolean dead = !r.owner().isEmpty() && !live.contains(r.owner());
            Class cls = !dead ? Class.LIVE : r.kind() == CovSites.Kind.ENTRY ? Class.DEAD_ENTRY : Class.IN_DEAD;
            String cause = cause(r.owner(), dead);
            String reason = allow.get(rel.getOrDefault(r.file(), r.file()) + "|" + r.kind() + "|" + r.line());
            if (cls == Class.LIVE && reason != null) { cls = Class.ALLOWED; cause = "allowed: " + reason; }
            missing.add(new Row(r, cls, r.owner().replaceAll("\\$\\d+$", ""), cause));
        }
        int u = 0;
        for (int id : found.keySet()) if (id < 1 || id > sites.regions.size()) u++;
        unknown = u;
    }

    /** The allow list of a project: TSV with the header {@code file kind line reason}; every row needs a reason. */
    static Map<String, String> readAllow(java.nio.file.Path f) {
        Map<String, String> m = new java.util.HashMap<>();
        if (!java.nio.file.Files.isRegularFile(f)) return m;
        try {
            List<String> ls = java.nio.file.Files.readAllLines(f);
            for (int i = 1; i < ls.size(); i++) {
                if (ls.get(i).isBlank()) continue;
                String[] c = ls.get(i).split("\t", -1);
                if (c.length != 4 || c[3].isBlank()) throw new IllegalArgumentException(f + ":" + (i + 1) + ": expected file, kind, line and a reason");
                m.put(c[0] + "|" + c[1] + "|" + c[2], c[3]);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return m;
    }

    /** The namespace and the name of a def as the class names carry them; an instance def loses its numeric suffix. */
    static String[] split(String sym) {
        String n = sym.replaceAll("\\$\\d+$", "");
        int i = n.lastIndexOf('.');
        return new String[] {i < 0 ? "" : n.substring(0, i), n.substring(i + 1)};
    }

    /** True if a class of the bytecode carries the def's namespace and name. */
    boolean present(String sym) {
        String[] k = split(sym);
        for (String c : classNames) {
            if (!c.startsWith(k[0] + "|")) continue;
            String d = c.substring(k[0].length() + 1);
            if (d.equals("Def$" + k[1]) || d.startsWith("Def$" + k[1] + "$") || d.startsWith("Clo$" + k[1] + "$")) return true;
        }
        return false;
    }

    private String cause(String owner, boolean dead) {
        if (owner.isEmpty()) return "no owner def";
        if (present(owner)) return "function is in the bytecode";
        List<String> callers = new ArrayList<>();
        for (var e : sites.calls.entrySet()) if (e.getValue().contains(owner) && (live.contains(e.getKey()) == !dead)) callers.add(e.getKey().replaceAll("\\$\\d+$", ""));
        String cls = "no class Def$" + split(owner)[1];
        if (dead) return cls + "; " + (callers.isEmpty() ? "no caller" : "callers all dead: " + String.join(", ", callers));
        return cls + " but applied by live code (inlined?): " + String.join(", ", callers);
    }

    /** Live functions with a missing marker: the audit fails on these. */
    List<Row> unexplained() { return missing.stream().filter(m -> m.cls() == Class.LIVE).toList(); }

    long count(Class c) { return missing.stream().filter(m -> m.cls() == c).count(); }

    String summary() {
        int sitesTotal = 0, dup = 0, most = 0;
        for (int n : found.values()) { sitesTotal += n; if (n > 1) dup++; most = Math.max(most, n); }
        TreeMap<String, Integer> byKind = new TreeMap<>();
        for (Row m : missing) byKind.merge(m.region().kind().name(), 1, Integer::sum);
        return "marker audit (bytecode): inserted " + inserted + ", distinct ids found " + found.size() + ", call sites " + sitesTotal
            + ", ids at more than one call site " + dup + " (at most " + most + ")" + ", missing " + missing.size() + " " + byKind
            + ": dead entry " + count(Class.DEAD_ENTRY) + ", inside a dead function " + count(Class.IN_DEAD) + ", allowed " + count(Class.ALLOWED) + ", LIVE function " + count(Class.LIVE)
            + ", ids not inserted " + unknown;
    }

    /** One line per missing marker: id, kind, file, line, function, class, evidence. */
    String tsv(Map<String, String> relFile) {
        StringBuilder sb = new StringBuilder("id\tkind\tfile\tline\tfunction\tclass\tcause\n");
        for (Row m : missing) {
            CovSites.Region r = m.region();
            sb.append(r.id()).append('\t').append(r.kind()).append('\t').append(relFile.getOrDefault(r.file(), r.file())).append('\t').append(r.line())
                .append('\t').append(m.function()).append('\t').append(m.cls()).append('\t').append(m.cause()).append('\n');
        }
        return sb.toString();
    }

    /** Scans the classes of the compiled program for {@code CovMark.hit(<int constant>)} calls. */
    static MarkerAudit scan(CompilationResult cr, CovSites sites, Map<String, String> allow, Map<String, String> rel) {
        TreeMap<Integer, Integer> found = new TreeMap<>();
        Set<String> names = new TreeSet<>();
        scala.jdk.javaapi.CollectionConverters.asJava(cr.root().classes()).forEach((cd, jc) -> {
            names.add(cd.packageName() + "|" + cd.displayName());
            scanClass(jc.bytecode(), found);
        });
        return new MarkerAudit(found, names, sites, allow, rel);
    }

    /** The ids of the marker calls in one class file, with the number of call sites. */
    static void scanClass(byte[] bytecode, Map<Integer, Integer> found) {
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
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
        }, 0);
    }
}
