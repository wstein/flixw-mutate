package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.util.List;

public final class SchemataTest {
    /** The span of the first occurrence of {@code text} at or after {@code from}. */
    static Span at(String src, String text, int from) {
        int i = src.indexOf(text, from);
        if (i < 0) throw new IllegalArgumentException(text);
        return new Span(i, i + text.length());
    }

    static Span at(String src, String text) { return at(src, text, 0); }

    static Site infix(String src, SiteKind kind, String whole, String left, String right) {
        Span w = at(src, whole);
        return new Site("Calc.flix", kind, w, at(src, left, w.start()), at(src, right, at(src, left, w.start()).end()));
    }

    public static void main(String[] args) {
        Check c = new Check("SchemataTest");

        c.test("comparison site becomes a helper call with both mutants", () -> {
            String src = "def f(x: Int32): Bool = x < 3\n";
            Schemata.Rendered r = Schemata.render(src, List.of(infix(src, SiteKind.LESS, "x < 3", "x", "3")), 1);
            eq("def f(x: Int32): Bool = (FlixwMutant.less(1, 2, (x), (3)))\n", r.text());
            eq(2, r.mutants().size());
            eq("<", r.mutants().get(0).from());
            eq("<=", r.mutants().get(0).to());
            eq(1, r.mutants().get(0).id());
            eq(">=", r.mutants().get(1).to());
            eq(2, r.mutants().get(1).id());
        });

        c.test("one-mutant kinds take one switch number", () -> {
            String src = "def f(x: Int32): Bool = x == 3\n";
            Schemata.Rendered r = Schemata.render(src, List.of(infix(src, SiteKind.EQUAL, "x == 3", "x", "3")), 7);
            eq("def f(x: Int32): Bool = (FlixwMutant.equal(7, (x), (3)))\n", r.text());
            eq(1, r.mutants().size());
            eq(7, r.mutants().get(0).id());
            eq("!=", r.mutants().get(0).to());
        });

        c.test("logical sites keep the right operand lazy", () -> {
            String src = "def f(a: Bool, b: Bool): Bool = a and b\n";
            Schemata.Rendered r = Schemata.render(src, List.of(infix(src, SiteKind.AND, "a and b", "a", "b")), 1);
            eq("def f(a: Bool, b: Bool): Bool = (FlixwMutant.conj(1, (a), () -> (b)))\n", r.text());
            eq("or", r.mutants().get(0).to());
        });

        c.test("negation site wraps the whole expression", () -> {
            String src = "def f(a: Bool): Bool = not a\n";
            Span w = at(src, "not a");
            Site s = new Site("Calc.flix", SiteKind.NOT, w, at(src, "a", w.start() + 4), null);
            Schemata.Rendered r = Schemata.render(src, List.of(s), 3);
            eq("def f(a: Bool): Bool = (FlixwMutant.flip(3, not a))\n", r.text());
            eq("(removed)", r.mutants().get(0).to());
        });

        c.test("a site inside an operand is rendered inside the outer call", () -> {
            String src = "def f(x: Int32, y: Int32): Bool = x < y + 1\n";
            Site outer = infix(src, SiteKind.LESS, "x < y + 1", "x", "y + 1");
            Site inner = infix(src, SiteKind.ADD, "y + 1", "y", "1");
            // The order of the list does not matter.
            Schemata.Rendered r = Schemata.render(src, List.of(inner, outer), 1);
            eq("def f(x: Int32, y: Int32): Bool = (FlixwMutant.less(1, 2, (x), ((FlixwMutant.add(3, (y), (1))))))\n", r.text());
            eq(List.of(1, 2, 3), r.mutants().stream().map(Mutant::id).toList());
            eq(SiteKind.LESS, r.mutants().get(0).site().kind());
            eq(SiteKind.ADD, r.mutants().get(2).site().kind());
        });

        c.test("text outside the sites is unchanged, comments in the operator gap are dropped", () -> {
            String src = "// head\nmod M {\n  def f(x: Int32): Int32 = x /* c */ + 1 // tail\n}\n";
            Schemata.Rendered r = Schemata.render(src, List.of(infix(src, SiteKind.ADD, "x /* c */ + 1", "x", "1")), 1);
            eq("// head\nmod M {\n  def f(x: Int32): Int32 = (FlixwMutant.add(1, (x), (1))) // tail\n}\n", r.text());
        });

        c.test("ids continue from firstId across sites in source order", () -> {
            String src = "def f(x: Int32): Bool = x < 3 and x > 0\n";
            Site a = infix(src, SiteKind.AND, "x < 3 and x > 0", "x < 3", "x > 0");
            Site l = infix(src, SiteKind.LESS, "x < 3", "x", "3");
            Site g = infix(src, SiteKind.GREATER, "x > 0", "x", "0");
            Schemata.Rendered r = Schemata.render(src, List.of(g, l, a), 10);
            eq(List.of(10, 11, 12, 13, 14), r.mutants().stream().map(Mutant::id).toList());
            eq(List.of(SiteKind.AND, SiteKind.LESS, SiteKind.LESS, SiteKind.GREATER, SiteKind.GREATER),
                r.mutants().stream().map(m -> m.site().kind()).toList());
        });

        c.test("no sites: the text is returned unchanged", () -> {
            Schemata.Rendered r = Schemata.render("def f(): Int32 = 1\n", List.of(), 1);
            eq("def f(): Int32 = 1\n", r.text());
            eq(0, r.mutants().size());
        });

        c.test("partial overlap is rejected", () -> {
            String src = "def f(x: Int32): Bool = x < 3 and x > 0\n";
            Site l = infix(src, SiteKind.LESS, "x < 3", "x", "3");
            Span w = new Span(l.whole().start() + 2, l.whole().end() + 5);
            Site bad = new Site("Calc.flix", SiteKind.AND, w, new Span(w.start(), w.start() + 1), new Span(w.end() - 1, w.end()));
            throwsA(IllegalArgumentException.class, () -> Schemata.render(src, List.of(l, bad), 1));
        });

        c.test("a site inside the operator text is rejected", () -> {
            String src = "def f(x: Int32): Bool = x < 3 and x > 0\n";
            Site a = infix(src, SiteKind.AND, "x < 3 and x > 0", "x < 3", "x > 0");
            Span inGap = at(src, "and");
            Site bad = new Site("Calc.flix", SiteKind.NOT, inGap, inGap, null);
            throwsA(IllegalArgumentException.class, () -> Schemata.render(src, List.of(a, bad), 1));
        });

        c.test("a site beyond the end of the text is rejected", () -> {
            Site bad = new Site("Calc.flix", SiteKind.NOT, new Span(0, 99), new Span(0, 1), null);
            throwsA(IllegalArgumentException.class, () -> Schemata.render("x\n", List.of(bad), 1));
        });

        c.test("usable keeps well-nested sites in rewrite order", () -> {
            String src = "def f(x: Int32): Bool = x < 3 and x > 0\n";
            Site a = infix(src, SiteKind.AND, "x < 3 and x > 0", "x < 3", "x > 0");
            Site l = infix(src, SiteKind.LESS, "x < 3", "x", "3");
            Site g = infix(src, SiteKind.GREATER, "x > 0", "x", "0");
            eq(List.of(a, l, g), Schemata.usable(List.of(g, l, a)));
        });

        c.test("usable drops a partial overlap and a site in the operator text", () -> {
            String src = "def f(x: Int32): Bool = x < 3 and x > 0\n";
            Site a = infix(src, SiteKind.AND, "x < 3 and x > 0", "x < 3", "x > 0");
            Site l = infix(src, SiteKind.LESS, "x < 3", "x", "3");
            Span w = new Span(l.whole().start() + 2, l.whole().end() + 5);
            Site partial = new Site("Calc.flix", SiteKind.AND, w, new Span(w.start(), w.start() + 1), new Span(w.end() - 1, w.end()));
            Span inGap = at(src, "and");
            Site inOperator = new Site("Calc.flix", SiteKind.NOT, inGap, inGap, null);
            eq(List.of(a, l), Schemata.usable(List.of(partial, inOperator, l, a)));
            Schemata.render(src, Schemata.usable(List.of(partial, inOperator, l, a)), 1);
        });

        c.test("usable drops a duplicate", () -> {
            String src = "def f(x: Int32): Bool = x < 3\n";
            Site l = infix(src, SiteKind.LESS, "x < 3", "x", "3");
            eq(List.of(l), Schemata.usable(List.of(l, l)));
        });

        c.test("position is 1-based line:col", () -> {
            String src = "ab\ncd\n";
            eq("1:1", Schemata.position(src, 0));
            eq("1:2", Schemata.position(src, 1));
            eq("2:1", Schemata.position(src, 3));
            eq("2:2", Schemata.position(src, 4));
        });

        c.test("position counts a CRLF line break as one line", () -> {
            eq("2:1", Schemata.position("ab\r\ncd\r\n", 4));
        });

        c.test("the module has the switch and one helper per kind with the right arity", () -> {
            String m = Schemata.module();
            yes(m.contains("mod FlixwMutant {"), "module header");
            yes(m.contains("unsafe IO"), "unsafe IO wrapper");
            yes(m.contains("\"flixw.mutant\""), "switch property");
            for (SiteKind k : SiteKind.values()) {
                String two = k.mutations().size() == 2 ? "n1: Int32, n2: Int32" : "n1: Int32";
                yes(m.contains("pub def " + k.helper() + "(" + two), "helper " + k.helper() + " with " + two);
            }
        });

        c.test("every mutation operator appears in its helper", () -> {
            String m = Schemata.module();
            yes(m.contains("if (on(n1)) x <= y else if (on(n2)) x >= y else x < y"), "less");
            yes(m.contains("if (on(n1)) x != y else x == y"), "equal");
            yes(m.contains("if (on(n1)) x - y else x + y"), "add");
            yes(m.contains("if (on(n1)) a or b() else a and b()"), "conj");
            yes(m.contains("if (on(n1)) not b else b"), "flip");
        });

        c.finish();
    }
}
