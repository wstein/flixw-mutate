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
 * missing marker gets one class: {@code DEAD_ENTRY} (the entry marker of a function the compiler dropped), {@code IN_DEAD}
 * (any other marker of such a function) or {@code LIVE} (the function is in the bytecode, or it was dropped but a live
 * function applies it, so its body may be inlined). A function is dropped when no class of the bytecode carries its name
 * ({@code Def$name}, {@code Clo$name$id}); it is dead when, in addition, every caller in the checked files is dropped
 * and dead too. A name followed by {@code $digits} may be a lifted lambda of another def or an overload: the audit errs towards
 * {@code LIVE}. Every {@code LIVE} row fails the audit.
 */
final class MarkerAudit {
    enum Class { DEAD_ENTRY, IN_DEAD, LIVE }

    record Row(CovSites.Region region, Class cls, String function, String cause) {}

    /** Number of call sites per marker id found in the bytecode. */
    final TreeMap<Integer, Integer> found;
    final List<Row> missing = new ArrayList<>();
    final int inserted;
    /** Ids in the bytecode that no region has (a harness fault). */
    final int unknown;
    private final Set<String> classNames;
    private final CovSites sites;
    private final Map<String, Boolean> deadMemo = new java.util.HashMap<>();

    MarkerAudit(Map<Integer, Integer> found, Set<String> classNames, CovSites sites) {
        this.found = new TreeMap<>(found);
        this.classNames = classNames;
        this.sites = sites;
        this.inserted = sites.regions.size();
        for (CovSites.Region r : sites.regions) {
            if (found.containsKey(r.id())) continue;
            boolean dead = r.owner().isEmpty() ? false : dead(r.owner(), new TreeSet<>());
            Class cls = !dead ? Class.LIVE : r.kind() == CovSites.Kind.ENTRY ? Class.DEAD_ENTRY : Class.IN_DEAD;
            missing.add(new Row(r, cls, r.owner(), cause(r.owner(), dead)));
        }
        int u = 0;
        for (int id : found.keySet()) if (id < 1 || id > sites.regions.size()) u++;
        unknown = u;
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

    /** A def is dead if it is dropped and no caller of the checked files is live. A def with no caller at all is dead. */
    private boolean dead(String sym, Set<String> path) {
        Boolean m = deadMemo.get(sym);
        if (m != null) return m;
        if (present(sym)) return false;
        if (!path.add(sym)) return true; // a cycle of dropped defs
        boolean dead = true;
        for (var e : sites.calls.entrySet()) if (e.getValue().contains(sym) && !dead(e.getKey(), path)) { dead = false; break; }
        path.remove(sym);
        deadMemo.put(sym, dead);
        return dead;
    }

    private String cause(String owner, boolean dead) {
        if (owner.isEmpty()) return "no owner def";
        if (present(owner)) return "function is in the bytecode";
        List<String> callers = new ArrayList<>();
        for (var e : sites.calls.entrySet()) if (e.getValue().contains(owner)) callers.add(e.getKey());
        if (dead) return "no class Def$" + split(owner)[1] + "; " + (callers.isEmpty() ? "no caller" : "callers all dropped: " + String.join(", ", callers));
        return "no class Def$" + split(owner)[1] + " but a live caller applies it (inlined?): " + String.join(", ", callers);
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
            + ": dead entry " + count(Class.DEAD_ENTRY) + ", inside a dead function " + count(Class.IN_DEAD) + ", LIVE function " + count(Class.LIVE)
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
    static MarkerAudit scan(CompilationResult cr, CovSites sites) {
        TreeMap<Integer, Integer> found = new TreeMap<>();
        Set<String> names = new TreeSet<>();
        scala.jdk.javaapi.CollectionConverters.asJava(cr.root().classes()).forEach((cd, jc) -> {
            names.add(cd.packageName() + "|" + cd.displayName());
            scanClass(jc.bytecode(), found);
        });
        return new MarkerAudit(found, names, sites);
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
