package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage sites from the typed AST of a checked program: regions (function entry, if branch, match arm, lambda
 * body) that get a marker, and the positions of all expressions (the executable lines). Spike code; the engine's
 * {@link FlixApi} and {@link SiteFinder#offset} are used as they are.
 */
final class CovSites {
    enum Kind { ENTRY, THEN, ELSE, ARM, LAMBDA }

    /**
     * A region with its marker id. {@code line}: the line of the construct (def line for ENTRY, line of the
     * if/match for branches, line of the lambda). {@code index}: branch number within the construct.
     */
    record Region(int id, Kind kind, String file, int start, int end, int line, int endLine, int index, String name) {}

    /** The start of one expression: where lines come from. */
    record Exec(String file, int offset, int line) {}

    final List<Region> regions = new ArrayList<>();
    final List<Exec> execs = new ArrayList<>();
    /** Count of typed-AST expression node classes inside the files' defs, for the construct inventory. */
    final Map<String, Integer> nodeCounts = new java.util.TreeMap<>();

    private final FlixApi api;
    private final Map<String, String> files;
    private final boolean lambdas;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int nextId = 1;
    private static final boolean DEBUG = Boolean.getBoolean("cov.debug");

    private CovSites(FlixApi api, Map<String, String> files, boolean lambdas) {
        this.api = api;
        this.files = files;
        this.lambdas = lambdas;
    }

    /** {@code files}: source name as the compiler reports it, mapped to the text; {@code lambdas}: region for lambda bodies. */
    static CovSites find(FlixApi api, Object root, Map<String, String> files, boolean lambdas) {
        CovSites s = new CovSites(api, files, lambdas);
        s.visit(root, null);
        return s;
    }

    private String fileOf(Object loc) {
        Object source = FlixApi.call(loc, "source");
        String n = String.valueOf(FlixApi.call(source, "sourceName"));
        return files.containsKey(n) ? n : null;
    }

    private static boolean real(Object loc) {
        return (Boolean) FlixApi.call(loc, "isReal") && !(Boolean) FlixApi.call(loc, "isSynthetic");
    }

    private static int line(Object loc, boolean end) {
        return (Integer) FlixApi.call(loc, end ? "endLine" : "startLine");
    }

    private void region(Kind kind, String file, Object exp, int line, int index, String name) {
        Object loc = FlixApi.call(exp, "loc");
        if (!real(loc) || !file.equals(fileOf(loc))) return;
        String text = files.get(file);
        int s = SiteFinder.offset(text, line(loc, false), (Integer) FlixApi.call(loc, "startCol"));
        int e = SiteFinder.offset(text, line(loc, true), (Integer) FlixApi.call(loc, "endCol"));
        if (s < 0 || e < s) return;
        regions.add(new Region(nextId++, kind, file, s, e, line, line(loc, true), index, name));
    }

    /** Depth-first, recursive: run on a thread with a large stack. {@code file}: the file of the enclosing def, or null. */
    private void visit(Object node, String file) {
        if (node == null || seen.put(node, Boolean.TRUE) != null) return;
        String name = FlixApi.nodeName(node);
        if (DEBUG && (name.equals("TypedAst$Def") || name.equals("TypedAst$Expr$ApplyDef") || name.startsWith("TypedAst$Instance")))
            System.err.println("DEBUG " + name + " children " + api.children(node).stream().map(c -> c == null ? "null" : c.getClass().getSimpleName()).toList()
                + (name.equals("TypedAst$Def") ? " loc " + FlixApi.call(node, "loc") + " sym " + FlixApi.call(node, "sym") : ""));
        if (name.equals("TypedAst$Def")) {
            Object loc = FlixApi.call(node, "loc");
            String f = real(loc) ? fileOf(loc) : null;
            if (f != null) {
                region(Kind.ENTRY, f, FlixApi.call(node, "exp"), line(loc, false), 0, String.valueOf(FlixApi.call(node, "sym")));
                file = f;
            } else {
                file = null;
            }
        } else if (file != null && name.startsWith("TypedAst$Expr$")) {
            Object loc = FlixApi.call(node, "loc");
            if (real(loc) && file.equals(fileOf(loc))) {
                String text = files.get(file);
                int o = SiteFinder.offset(text, line(loc, false), (Integer) FlixApi.call(loc, "startCol"));
                if (o >= 0) {
                    execs.add(new Exec(file, o, line(loc, false)));
                    nodeCounts.merge(name.substring("TypedAst$Expr$".length()), 1, Integer::sum);
                }
                switch (name) {
                    case "TypedAst$Expr$IfThenElse" -> {
                        region(Kind.THEN, file, FlixApi.call(node, "exp2"), line(loc, false), 0, "");
                        region(Kind.ELSE, file, FlixApi.call(node, "exp3"), line(loc, false), 1, "");
                    }
                    case "TypedAst$Expr$Match" -> {
                        int i = 0;
                        for (Object rule : api.children(FlixApi.call(node, "rules")))
                            region(Kind.ARM, file, FlixApi.call(rule, "exp"), line(loc, false), i++, "");
                    }
                    default -> { }
                }
            }
        }
        // A lambda with several parameters is desugared to nested lambdas with synthetic locations: the region is the
        // first body that is not a lambda (its location is real).
        if (file != null && lambdas && name.equals("TypedAst$Expr$Lambda")) {
            Object body = FlixApi.call(node, "exp");
            if (!FlixApi.nodeName(body).equals("TypedAst$Expr$Lambda"))
                region(Kind.LAMBDA, file, body, line(FlixApi.call(body, "loc"), false), 0, "");
        }
        if (name.startsWith("TypedAst$Expr$Fixpoint")) return;
        // The typed AST keeps trait instances in a ListMap, which FlixApi.children does not descend into.
        if (name.endsWith("ListMap")) {
            visit(FlixApi.call(node, "m"), file);
            return;
        }
        for (Object c : api.children(node)) visit(c, file);
    }

    // --- rendering -------------------------------------------------------------------------------------------

    /** The marker module the instrumented program calls; {@code target} is the Java class of the marker. */
    static String module(String target) {
        return "// Generated by the coverage spike.\nmod FlixwCov {\n    import " + target + "\n\n"
            + "    pub def hit(n: Int32): Bool = unsafe IO { " + target.substring(target.lastIndexOf('.') + 1) + ".hit(n) }\n}\n";
    }

    /** The text with every region of the file wrapped: {@code if (FlixwCov.hit(n)) bug!(..) else (E)}. */
    String render(String file) {
        String text = files.get(file);
        List<Region> mine = new ArrayList<>();
        for (Region r : regions) if (r.file().equals(file)) mine.add(r);
        mine.sort(Comparator.<Region>comparingInt(Region::start).thenComparing(r -> -r.end()).thenComparingInt(Region::id));
        StringBuilder sb = new StringBuilder();
        int[] pos = {0};
        emit(text, mine, new int[] {0}, Integer.MAX_VALUE, sb, pos);
        sb.append(text, pos[0], text.length());
        return sb.toString();
    }

    /** Emits the regions of the sorted list from {@code at[0]} that start before {@code limit}. */
    private static void emit(String text, List<Region> sorted, int[] at, int limit, StringBuilder sb, int[] pos) {
        while (at[0] < sorted.size() && sorted.get(at[0]).start() < limit) {
            Region r = sorted.get(at[0]++);
            sb.append(text, pos[0], r.start());
            pos[0] = r.start();
            sb.append("if (FlixwCov.hit(").append(r.id()).append(")) bug!(\"flixw-cov\") else (");
            emit(text, sorted, at, r.end(), sb, pos);
            sb.append(text, pos[0], r.end());
            pos[0] = r.end();
            sb.append(")");
        }
    }
}
