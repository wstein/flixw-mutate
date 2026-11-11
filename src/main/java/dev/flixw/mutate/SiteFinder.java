package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks mutation sites from the typed AST of a checked program. Only expressions in the given files are
 * visited (the standard library and other files are skipped at the root of each expression), Datalog
 * constraints are skipped, and a site is kept only if the source text around it has exactly the shape the
 * rewrite expects (operator between the operands, balanced parentheses, no comment in the way).
 */
final class SiteFinder {
    private static final Set<String> NUMERIC =
        Set.of("Int8", "Int16", "Int32", "Int64", "Float32", "Float64", "BigInt", "BigDecimal");

    private final FlixApi api;
    private final Map<String, String> files;
    private final Map<String, List<Site>> found = new java.util.TreeMap<>();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int skipped;

    private SiteFinder(FlixApi api, Map<String, String> files) {
        this.api = api;
        this.files = files;
    }

    /** The sites per file (key as in {@code files}), rewrite order, and the number of candidates left out. */
    record Result(Map<String, List<Site>> sites, int skipped) {}

    /**
     * @param root  the typed AST root of the checked program
     * @param files file name as the compiler reports it, mapped to the file's text
     */
    static Result find(FlixApi api, Object root, Map<String, String> files) {
        SiteFinder f = new SiteFinder(api, files);
        f.walk(root);
        Map<String, List<Site>> out = new java.util.TreeMap<>();
        for (Map.Entry<String, List<Site>> e : f.found.entrySet()) out.put(e.getKey(), Schemata.usable(e.getValue()));
        int dropped = 0;
        for (Map.Entry<String, List<Site>> e : f.found.entrySet()) dropped += e.getValue().size() - out.get(e.getKey()).size();
        return new Result(out, f.skipped + dropped);
    }

    private void walk(Object start) {
        // Iterative: the typed AST of a long function is deep.
        List<Object> stack = new ArrayList<>();
        stack.add(start);
        while (!stack.isEmpty()) {
            Object node = stack.remove(stack.size() - 1);
            if (node == null || seen.put(node, Boolean.TRUE) != null) continue;
            String name = FlixApi.nodeName(node);
            if (name.startsWith("TypedAst$Expr$")) {
                if (name.startsWith("TypedAst$Expr$Fixpoint")) continue;
                Object loc = FlixApi.call(node, "loc");
                String file = fileOf(loc);
                if (file == null) continue;
                visit(node, name, loc, file);
            }
            stack.addAll(api.children(node));
        }
    }

    private String fileOf(Object loc) {
        Object source = FlixApi.call(loc, "source");
        String n = String.valueOf(FlixApi.call(source, "sourceName"));
        return files.containsKey(n) ? n : null;
    }

    private void visit(Object node, String name, Object loc, String file) {
        SiteKind kind = null;
        List<Object> operands = new ArrayList<>();
        switch (name) {
            case "TypedAst$Expr$ApplySig" -> {
                Object sym = FlixApi.call(FlixApi.call(FlixApi.call(node, "symUse"), "sym"), "toString");
                kind = SiteKind.byTypedAstName(String.valueOf(sym)).orElse(null);
                if (kind != null && kind.shape() != SiteKind.Shape.COMPARE && kind.shape() != SiteKind.Shape.ARITHMETIC) kind = null;
                for (Object e : javaList(FlixApi.call(node, "exps"))) operands.add(e);
            }
            case "TypedAst$Expr$Binary" -> {
                String op = FlixApi.call(node, "sop").getClass().getName();
                if (op.endsWith("BoolOp$And$")) kind = SiteKind.AND;
                else if (op.endsWith("BoolOp$Or$")) kind = SiteKind.OR;
                operands.add(FlixApi.call(node, "exp1"));
                operands.add(FlixApi.call(node, "exp2"));
            }
            case "TypedAst$Expr$Unary" -> {
                if (FlixApi.call(node, "sop").getClass().getName().endsWith("BoolOp$Not$")) kind = SiteKind.NOT;
                operands.add(FlixApi.call(node, "exp"));
            }
            default -> { }
        }
        if (kind == null) return;
        boolean unary = kind == SiteKind.NOT;
        if (operands.size() != (unary ? 1 : 2) || !isReal(loc)) {
            skipped++;
            return;
        }
        if (kind.shape() == SiteKind.Shape.ARITHMETIC && !numeric(operands)) return;
        String text = files.get(file);
        Span whole = span(text, loc);
        Span left = span(text, FlixApi.call(operands.get(0), "loc"));
        Span right = unary ? null : span(text, FlixApi.call(operands.get(1), "loc"));
        if (whole == null || left == null || (!unary && right == null) || !fits(text, kind, whole, left, right)) {
            skipped++;
            return;
        }
        found.computeIfAbsent(file, k -> new ArrayList<>()).add(new Site(file, kind, whole, left, right));
    }

    private boolean numeric(List<Object> operands) {
        String t0 = String.valueOf(FlixApi.call(operands.get(0), "tpe"));
        String t1 = String.valueOf(FlixApi.call(operands.get(1), "tpe"));
        return t0.equals(t1) && NUMERIC.contains(t0);
    }

    private static boolean isReal(Object loc) {
        return (Boolean) FlixApi.call(loc, "isReal") && !(Boolean) FlixApi.call(loc, "isSynthetic");
    }

    private static boolean fits(String text, SiteKind kind, Span whole, Span left, Span right) {
        try {
            if (kind == SiteKind.NOT) {
                return whole.start() <= left.start() && left.end() <= whole.end()
                    && Gap.fitsUnary(text.substring(whole.start(), left.start()), kind.operator(), text.substring(left.end(), whole.end()));
            }
            return whole.start() <= left.start() && left.end() <= right.start() && right.end() <= whole.end()
                && Gap.fitsInfix(text.substring(whole.start(), left.start()), text.substring(left.end(), right.start()),
                    text.substring(right.end(), whole.end()), kind.operator());
        } catch (IndexOutOfBoundsException e) {
            return false;
        }
    }

    /** Offsets of a location in the text (1-based line and column, end column exclusive), or null. */
    private static Span span(String text, Object loc) {
        int start = offset(text, (Integer) FlixApi.call(loc, "startLine"), (Integer) FlixApi.call(loc, "startCol"));
        int end = offset(text, (Integer) FlixApi.call(loc, "endLine"), (Integer) FlixApi.call(loc, "endCol"));
        return start < 0 || end < start ? null : new Span(start, end);
    }

    /** The offset of line:col (1-based), or -1 if the line does not exist. */
    static int offset(String text, int line, int col) {
        int pos = 0;
        for (int l = 1; l < line; l++) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) return -1;
            pos = nl + 1;
        }
        int o = pos + col - 1;
        return o > text.length() ? -1 : o;
    }

    private List<Object> javaList(Object scalaList) {
        return api.children(scalaList);
    }
}
