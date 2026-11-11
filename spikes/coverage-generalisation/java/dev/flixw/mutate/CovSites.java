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
     * if/match for branches, line of the lambda). {@code index}: branch number within the construct. {@code owner}: the symbol
     * of the def the region belongs to (also when the def has no entry region of its own).
     */
    record Region(int id, Kind kind, String file, int start, int end, int line, int endLine, int index, String name, String owner) {}

    /** The start of one expression: where lines come from. */
    record Exec(String file, int offset, int line) {}

    final List<Region> regions = new ArrayList<>();
    final List<Exec> execs = new ArrayList<>();
    /** Count of typed-AST expression node classes inside the files' defs, for the construct inventory. */
    final Map<String, Integer> nodeCounts = new java.util.TreeMap<>();
    /** Symbols of the defs of the checked files. */
    final java.util.Set<String> defs = new java.util.TreeSet<>();
    /** Call graph of the checked files: def symbol to the def symbols it applies. */
    final Map<String, java.util.Set<String>> calls = new java.util.TreeMap<>();
    /** Symbols of the defs in the test files: they are entry points of the program, whatever the call graph says. */
    final java.util.Set<String> roots = new java.util.TreeSet<>();
    private final java.util.Set<String> testFiles;
    private String owner = "";

    private final FlixApi api;
    private final Map<String, String> files;
    private final boolean lambdas;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int nextId = 1;
    /** Regions left out because the span of the expression is not the text of an expression (unbalanced brackets). */
    int skipped;
    private static final boolean DEBUG = Boolean.getBoolean("cov.debug");

    private CovSites(FlixApi api, Map<String, String> files, boolean lambdas, java.util.Set<String> testFiles) {
        this.testFiles = testFiles;
        this.api = api;
        this.files = files;
        this.lambdas = lambdas;
    }

    /** {@code files}: source name as the compiler reports it, mapped to the text; {@code lambdas}: region for lambda bodies. */
    static CovSites find(FlixApi api, Object root, Map<String, String> files, boolean lambdas) {
        return find(api, root, files, lambdas, java.util.Set.of());
    }

    /** {@code testFiles}: source names of the test files; their defs are roots of the call graph and get no regions. */
    static CovSites find(FlixApi api, Object root, Map<String, String> files, boolean lambdas, java.util.Set<String> testFiles) {
        CovSites s = new CovSites(api, files, lambdas, testFiles);
        s.visit(root, null);
        s.renumber();
        return s;
    }

    /**
     * The walk order follows hash maps of the compiler: sort the regions by file, start, end (longer first) and kind and
     * number them again, so that the ids are the same in every run on the same sources.
     */
    private void renumber() {
        regions.sort(Comparator.comparing(Region::file).thenComparingInt(Region::start).thenComparing(r -> -r.end())
            .thenComparing(r -> r.kind().ordinal()).thenComparingInt(Region::index));
        List<Region> sorted = new ArrayList<>();
        for (Region r : regions) sorted.add(new Region(sorted.size() + 1, r.kind(), r.file(), r.start(), r.end(), r.line(), r.endLine(), r.index(), r.name(), r.owner()));
        regions.clear();
        regions.addAll(sorted);
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

    /** Instance defs carry a numeric suffix ({@code toString$57116}) that changes from run to run: replaced by the line. */
    private static String name(String sym, int line) {
        return sym.replaceAll("\\$\\d+$", "") + (sym.matches(".*\\$\\d+$") ? "@" + line : "");
    }

    private void region(Kind kind, String file, Object exp, int line, int index, String name) {
        Object loc = FlixApi.call(exp, "loc");
        if (!real(loc) || !file.equals(fileOf(loc))) return;
        String text = files.get(file);
        int s = SiteFinder.offset(text, line(loc, false), (Integer) FlixApi.call(loc, "startCol"));
        int e = SiteFinder.offset(text, line(loc, true), (Integer) FlixApi.call(loc, "endCol"));
        if (s < 0 || e < s) return;
        if (!balanced(text.substring(s, e))) { skipped++; return; }
        regions.add(new Region(nextId++, kind, file, s, e, line, line(loc, true), index, name, owner));
    }

    /**
     * True if the brackets of the text balance, outside strings, characters and comments. The typed AST has spans that miss
     * a bracket (a record literal's location starts after its opening brace); wrapping such a span breaks the program.
     */
    static boolean balanced(String t) {
        int depth = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') {
                for (i++; i < t.length() && t.charAt(i) != '"'; i++) if (t.charAt(i) == '\\') i++;
            } else if (c == '\'' && i + 2 < t.length() && (t.charAt(i + 2) == '\'' || t.charAt(i + 1) == '\\')) {
                i = t.indexOf('\'', t.charAt(i + 1) == '\\' ? i + 3 : i + 2);
                if (i < 0) return false;
            } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '/') {
                i = t.indexOf('\n', i);
                if (i < 0) break;
            } else if (c == '/' && i + 1 < t.length() && t.charAt(i + 1) == '*') {
                i = t.indexOf("*/", i + 2);
                if (i < 0) return false;
                i++;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth < 0) return false;
            }
        }
        return depth == 0;
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
                owner = String.valueOf(FlixApi.call(node, "sym"));
                defs.add(owner);
                region(Kind.ENTRY, f, FlixApi.call(node, "exp"), line(loc, false), 0, name(String.valueOf(FlixApi.call(node, "sym")), line(loc, false)));
                file = f;
            } else {
                file = null;
                owner = real(loc) && testFiles.contains(String.valueOf(FlixApi.call(FlixApi.call(loc, "source"), "sourceName"))) ? String.valueOf(FlixApi.call(node, "sym")) : "";
                if (!owner.isEmpty()) roots.add(owner);
            }
        } else if (name.equals("TypedAst$Expr$ApplyDef") && !owner.isEmpty()) {
            calls.computeIfAbsent(owner, k -> new java.util.TreeSet<>()).add(String.valueOf(FlixApi.call(FlixApi.call(node, "symUse"), "sym")));
        }
        if (file != null && name.startsWith("TypedAst$Expr$")) {
            Object loc = FlixApi.call(node, "loc");
            if (real(loc) && file.equals(fileOf(loc))) {
                String text = files.get(file);
                int o = SiteFinder.offset(text, line(loc, false), (Integer) FlixApi.call(loc, "startCol"));
                if (o >= 0) {
                    execs.add(new Exec(file, o, line(loc, false)));
                    nodeCounts.merge(name.substring("TypedAst$Expr$".length()), 1, Integer::sum);
                }
                // A destructuring let is desugared to a match whose arm span starts too early: only constructs that start
                // with their keyword in the source get branch regions.
                boolean written = o >= 0 && (name.equals("TypedAst$Expr$IfThenElse") ? text.startsWith("if", o)
                    : !name.equals("TypedAst$Expr$Match") || text.startsWith("match", o));
                String construct = name;
                if (!written && (name.equals("TypedAst$Expr$IfThenElse") || name.equals("TypedAst$Expr$Match"))) {
                    skipped++;
                    construct = "";
                }
                switch (construct) {
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

    /** The text with every region of the file wrapped: {@code (if (FlixwCov.hit(n)) bug!(..) else { E })}. */
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
            // Braces, not parentheses: the expression may be a sequence {@code a; b}. A leading minus after a brace would read as a
            // record restriction, so that case keeps its own parentheses.
            boolean minus = text.substring(r.start(), r.end()).stripLeading().startsWith("-");
            sb.append("(if (FlixwCov.hit(").append(r.id()).append(")) bug!(\"flixw-cov\") else { ").append(minus ? "(" : "");
            emit(text, sorted, at, r.end(), sb, pos);
            sb.append(text, pos[0], r.end());
            pos[0] = r.end();
            sb.append(minus ? ") })" : " })");
        }
    }
}
