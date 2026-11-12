package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the coverage regions from the typed AST of a checked program: the body of every def, the two branches of
 * an {@code if}, every arm of a {@code match} and the body of a lambda, and the start of every expression (the
 * executable lines). Only defs and expressions in the given files are visited. A region is kept only if the text
 * of its span has balanced brackets: the typed AST has spans that miss a bracket, and wrapping such a span would
 * break the program. A destructuring {@code let}, which is desugared to a {@code match}, gets no branch regions.
 * The method is that of the coverage spike (#22), which has the ground truth for it.
 */
final class RegionFinder {
    private final FlixApi api;
    private final Map<String, String> files;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final List<Region> regions = new ArrayList<>();
    private final List<Exec> execs = new ArrayList<>();

    private RegionFinder(FlixApi api, Map<String, String> files) {
        this.api = api;
        this.files = files;
    }

    /** The regions (in walk order, not sorted) and the expression starts. */
    record Result(List<Region> regions, List<Exec> execs) {}

    /**
     * @param root  the typed AST root of the checked program
     * @param files file name as the compiler reports it, mapped to the file's text
     */
    static Result find(FlixApi api, Object root, Map<String, String> files) {
        RegionFinder f = new RegionFinder(api, files);
        f.walk(root);
        return new Result(f.regions, f.execs);
    }

    /** A node with the file of the def around it, or null outside the defs of the files. */
    private record Frame(Object node, String file) {}

    private void walk(Object start) {
        // Iterative: the typed AST of a long function is deep. Children go on the stack in reverse, so the walk
        // is depth-first in the order of the children.
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame(start, null));
        while (!stack.isEmpty()) {
            Frame frame = stack.remove(stack.size() - 1);
            Object node = frame.node();
            String file = frame.file();
            if (node == null || seen.put(node, Boolean.TRUE) != null) continue;
            String name = FlixApi.nodeName(node);
            if (name.equals("TypedAst$Def")) {
                Object loc = FlixApi.call(node, "loc");
                file = real(loc) ? fileOf(loc) : null;
                if (file != null) region(Point.Kind.FUNCTION, file, FlixApi.call(node, "exp"), line(loc, false));
            }
            if (file != null && name.startsWith("TypedAst$Expr$")) expression(node, name, file);
            // A lambda with several parameters is desugared to nested lambdas with synthetic locations: the region is
            // the first body that is not a lambda (its location is real).
            if (file != null && name.equals("TypedAst$Expr$Lambda")) {
                Object body = FlixApi.call(node, "exp");
                if (!FlixApi.nodeName(body).equals("TypedAst$Expr$Lambda"))
                    region(Point.Kind.LAMBDA, file, body, line(FlixApi.call(body, "loc"), false));
            }
            if (name.startsWith("TypedAst$Expr$Fixpoint")) continue;
            List<Object> kids = api.children(node);
            for (int i = kids.size() - 1; i >= 0; i--) stack.add(new Frame(kids.get(i), file));
        }
    }

    private void expression(Object node, String name, String file) {
        Object loc = FlixApi.call(node, "loc");
        if (!real(loc) || !file.equals(fileOf(loc))) return;
        String text = files.get(file);
        int line = line(loc, false);
        int o = SiteFinder.offset(text, line, (Integer) FlixApi.call(loc, "startCol"));
        if (o >= 0) execs.add(new Exec(file, o, line));
        // A destructuring let is desugared to a match whose arm span starts too early: only constructs that start
        // with their keyword in the source get branch regions.
        boolean isIf = name.equals("TypedAst$Expr$IfThenElse");
        boolean isMatch = name.equals("TypedAst$Expr$Match");
        if (isIf || isMatch) {
            boolean written = o >= 0 && text.startsWith(isIf ? "if" : "match", o);
            if (!written) return;
        }
        if (isIf) {
            region(Point.Kind.THEN, file, FlixApi.call(node, "exp2"), line);
            region(Point.Kind.ELSE, file, FlixApi.call(node, "exp3"), line);
        } else if (isMatch) {
            for (Object rule : api.children(FlixApi.call(node, "rules")))
                region(Point.Kind.ARM, file, FlixApi.call(rule, "exp"), line);
        }
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

    private void region(Point.Kind kind, String file, Object exp, int line) {
        Object loc = FlixApi.call(exp, "loc");
        if (!real(loc) || !file.equals(fileOf(loc))) return;
        String text = files.get(file);
        int s = SiteFinder.offset(text, line(loc, false), (Integer) FlixApi.call(loc, "startCol"));
        int e = SiteFinder.offset(text, line(loc, true), (Integer) FlixApi.call(loc, "endCol"));
        if (s < 0 || e < s) return;
        if (!balanced(text.substring(s, e))) return;
        regions.add(new Region(file, kind, new Span(s, e), line));
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
}
