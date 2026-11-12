package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks the Datalog rules and facts from the typed AST of a checked program: every constraint of a fixpoint
 * constraint set in the given files, a rule if it has a body atom or guard, a fact if it has none. Unlike the
 * other finders this one walks into the constraint sets. The mapping from a solve's rule to its source line is the
 * one of the coverage spike (#22): by predicate names, see {@link DatalogMap}.
 */
final class DatalogFinder {
    private final FlixApi api;
    private final Map<String, String> files;
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private final List<DatalogSite> sites = new ArrayList<>();
    private int skipped;

    private DatalogFinder(FlixApi api, Map<String, String> files) {
        this.api = api;
        this.files = files;
    }

    /** The sites (walk order, not sorted) and the number of constraints left out (a span the text cannot give). */
    record Result(List<DatalogSite> sites, int skipped) {}

    /**
     * @param root  the typed AST root of the checked program
     * @param files file name as the compiler reports it, mapped to the file's text
     */
    static Result find(FlixApi api, Object root, Map<String, String> files) {
        DatalogFinder f = new DatalogFinder(api, files);
        f.walk(root);
        return new Result(f.sites, f.skipped);
    }

    private record Frame(Object node, String def) {}

    private void walk(Object start) {
        List<Frame> stack = new ArrayList<>();
        stack.add(new Frame(start, ""));
        while (!stack.isEmpty()) {
            Frame frame = stack.remove(stack.size() - 1);
            Object node = frame.node();
            if (node == null || seen.put(node, Boolean.TRUE) != null) continue;
            String name = FlixApi.nodeName(node);
            String def = frame.def();
            if (name.equals("TypedAst$Def")) def = String.valueOf(FlixApi.call(node, "sym"));
            if (name.equals("TypedAst$Constraint")) constraint(node, def);
            List<Object> kids = api.children(node);
            for (int i = kids.size() - 1; i >= 0; i--) stack.add(new Frame(kids.get(i), def));
        }
    }

    private void constraint(Object node, String def) {
        Object loc = FlixApi.call(node, "loc");
        if (!(Boolean) FlixApi.call(loc, "isReal") || (Boolean) FlixApi.call(loc, "isSynthetic")) return;
        String file = String.valueOf(FlixApi.call(FlixApi.call(loc, "source"), "sourceName"));
        String text = files.get(file);
        if (text == null) return;
        int line = (Integer) FlixApi.call(loc, "startLine");
        int s = SiteFinder.offset(text, line, (Integer) FlixApi.call(loc, "startCol"));
        int e = SiteFinder.offset(text, (Integer) FlixApi.call(loc, "endLine"), (Integer) FlixApi.call(loc, "endCol"));
        if (s < 0 || e < s) {
            skipped++;
            return;
        }
        List<Object> body = api.children(FlixApi.call(node, "body"));
        String head = String.valueOf(FlixApi.call(FlixApi.call(FlixApi.call(node, "head"), "pred"), "name"));
        if (body.isEmpty()) {
            sites.add(new DatalogSite(Point.Kind.FACT, file, new Span(s, e), line, def, factKey(text.substring(s, e))));
            return;
        }
        List<String> names = new ArrayList<>();
        for (Object b : body)
            if (FlixApi.nodeName(b).equals("TypedAst$Predicate$Body$Atom")) names.add(String.valueOf(FlixApi.call(FlixApi.call(b, "pred"), "name")));
        sites.add(new DatalogSite(Point.Kind.RULE, file, new Span(s, e), line, def, ruleKey(head, names)));
    }

    /** {@code Head<-Body1,Body2}: the signature the solver hook writes for a rule. */
    static String ruleKey(String head, List<String> bodyAtoms) { return head + "<-" + String.join(",", bodyAtoms); }

    /** The text of a fact without white space (outside string literals) and the final dot. */
    static String factKey(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < text.length()) sb.append(text.charAt(++i));
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        int n = sb.length();
        return n > 0 && sb.charAt(n - 1) == '.' ? sb.substring(0, n - 1) : sb.toString();
    }
}
