package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.util.List;

/** The numbering of mutants, regions and line parts in one id space, and the rendering of both kinds of site. */
public final class SitePlanTest {
    // line 1: def f(x: Int32): Bool = if (x < 3) x + 1 > 0 else false
    static final String SRC = "def f(x: Int32): Bool = if (x < 3) x + 1 > 0 else false\ndef g(): Int32 = -1\n";
    static final String F = "src/A.flix";

    static Span at(String text, int from) { return SchemataTest.at(SRC, text, from); }

    static Span at(String text) { return SchemataTest.at(SRC, text); }

    static Site less() { return SchemataTest.infix(SRC, SiteKind.LESS, "x < 3", "x", "3"); }

    static Site greater() { return SchemataTest.infix(SRC, SiteKind.GREATER, "x + 1 > 0", "x + 1", "0"); }

    static Site add() { return SchemataTest.infix(SRC, SiteKind.ADD, "x + 1", "x", "1"); }

    static Region region(Point.Kind kind, Span span, int line) { return new Region("/p/src/A.flix", kind, span, line); }

    static SitePlan plan(List<Site> sites, List<Region> regions, List<Exec> execs) {
        return SitePlan.build(List.of(new SitePlan.Input(F, SRC, sites, regions, execs)));
    }

    static String rows(SiteTable t) {
        StringBuilder sb = new StringBuilder();
        for (Point p : t.points()) sb.append(p.id()).append(' ').append(p.kind()).append(' ').append(p.line()).append(' ')
            .append(p.span().start()).append('-').append(p.span().end()).append('\n');
        return sb.toString();
    }

    public static void main(String[] args) {
        Check c = new Check("SitePlanTest");

        c.test("Datalog rules and facts are rows of the same table, in order, without a marker and without a rewrite", () -> {
            Span fn = at("x < 3");
            DatalogSite rule = new DatalogSite(Point.Kind.RULE, "/p/src/A.flix", at("x + 1 > 0"), 1, "A.f", "A<-B");
            DatalogSite fact = new DatalogSite(Point.Kind.FACT, "/p/src/A.flix", at("false"), 1, "A.f", "B(1)");
            SitePlan p = SitePlan.build(List.of(new SitePlan.Input(F, SRC, List.of(less()), List.of(), List.of(), List.of(fact, rule))));
            eq("1 MUTANT 1 " + fn.start() + "-" + fn.end() + "\n"
                + "2 MUTANT 1 " + fn.start() + "-" + fn.end() + "\n"
                + "3 RULE 1 " + rule.span().start() + "-" + rule.span().end() + "\n"
                + "4 FACT 1 " + fact.span().start() + "-" + fact.span().end() + "\n", rows(p.table()));
            eq(java.util.Map.of(3, rule, 4, fact), p.datalogSites());
            eq(1, p.items(F).size()); // only the mutation site is rewritten
        });

        c.test("at an equal span a Datalog row comes after the mutants", () -> {
            Span sp = less().whole();
            DatalogSite fact = new DatalogSite(Point.Kind.FACT, "/p/src/A.flix", sp, 1, "A.f", "B(1)");
            SitePlan p = SitePlan.build(List.of(new SitePlan.Input(F, SRC, List.of(less()), List.of(), List.of(), List.of(fact))));
            eq("1 MUTANT 1 " + sp.start() + "-" + sp.end() + "\n" + "2 MUTANT 1 " + sp.start() + "-" + sp.end() + "\n"
                + "3 FACT 1 " + sp.start() + "-" + sp.end() + "\n", rows(p.table()));
        });

        c.test("a plan without Datalog sites has none", () -> eq(java.util.Map.of(), plan(List.of(less()), List.of(), List.of()).datalogSites()));

        c.test("mutants, regions and line parts share one dense id space in the order of file, offset and wider span first", () -> {
            Span body = at("if (x < 3) x + 1 > 0 else false");
            Span thenB = at("x + 1 > 0");
            Span elseB = at("false");
            SitePlan p = plan(List.of(less(), greater(), add()),
                List.of(region(Point.Kind.ELSE, elseB, 1), region(Point.Kind.FUNCTION, body, 1), region(Point.Kind.THEN, thenB, 1)),
                List.of(new Exec("/p/src/A.flix", body.start(), 1)));
            // The then branch has the span of the greater-than site: the region is first (it wraps the site).
            String lw = less().whole().start() + "-" + less().whole().end();
            String tw = thenB.start() + "-" + thenB.end();
            eq("1 FUNCTION 1 " + body.start() + "-" + body.end() + "\n"
                + "2 LINE 1 " + body.start() + "-" + body.end() + "\n"
                + "3 MUTANT 1 " + lw + "\n"
                + "4 MUTANT 1 " + lw + "\n"
                + "5 THEN 1 " + tw + "\n"
                + "6 MUTANT 1 " + tw + "\n"
                + "7 MUTANT 1 " + tw + "\n"
                + "8 MUTANT 1 " + at("x + 1").start() + "-" + at("x + 1").end() + "\n"
                + "9 ELSE 1 " + elseB.start() + "-" + elseB.end() + "\n", rows(p.table()));
        });

        c.test("ids do not depend on the order of the input", () -> {
            Span body = at("if (x < 3) x + 1 > 0 else false");
            Span elseB = at("false");
            List<Region> rs = List.of(region(Point.Kind.FUNCTION, body, 1), region(Point.Kind.ELSE, elseB, 1));
            SitePlan a = plan(List.of(less(), greater()), rs, List.of());
            SitePlan b = plan(List.of(greater(), less()), List.of(rs.get(1), rs.get(0)), List.of());
            eq(rows(a.table()), rows(b.table()));
        });

        c.test("the items carry the ids of the table: a region its own, a site its first mutant", () -> {
            Span body = at("if (x < 3) x + 1 > 0 else false");
            SitePlan p = plan(List.of(less()), List.of(region(Point.Kind.FUNCTION, body, 1)), List.of());
            List<Schemata.Item> items = p.items(F);
            eq(2, items.size());
            eq(1, items.get(0).id());
            eq(null, items.get(0).site());
            eq(2, items.get(1).id());
            eq(Point.Kind.MUTANT, p.table().get(2).kind());
            eq(Point.Kind.MUTANT, p.table().get(3).kind());
            eq(List.of(), p.items("src/other.flix"));
        });

        c.test("a region that overlaps a site badly is left out and counted, the others stay", () -> {
            Span inGap = new Span(at("x < 3").start() + 1, at("x < 3").start() + 4); // " < " : the operator text
            Span partial = new Span(at("x < 3").start() + 2, at("x < 3").end() + 3);   // starts in the site, ends after it
            Span ok = at("x + 1 > 0");
            SitePlan p = plan(List.of(less(), greater()),
                List.of(region(Point.Kind.THEN, inGap, 1), region(Point.Kind.ELSE, partial, 1), region(Point.Kind.ARM, ok, 1)), List.of());
            eq(2, p.skippedRegions());
            long regions = p.table().points().stream().filter(q -> q.kind() == Point.Kind.ARM).count();
            eq(1L, regions);
            eq(4L, p.table().points().stream().filter(q -> q.kind() == Point.Kind.MUTANT).count());
        });

        c.test("a region in an operand of a site, around a site and with the span of one is kept", () -> {
            SitePlan p = plan(List.of(less(), greater(), add()), List.of(
                region(Point.Kind.THEN, at("x + 1"), 1),           // is the left operand of greater and has the span of add
                region(Point.Kind.ELSE, at("if (x < 3) x + 1 > 0 else false"), 1)), List.of());
            eq(0, p.skippedRegions());
        });

        c.test("a region that overlaps another one without nesting is left out, a duplicate too", () -> {
            Span a = at("(x < 3) x");
            Span b = at("x < 3) x + 1");
            SitePlan p = plan(List.of(), List.of(region(Point.Kind.THEN, a, 1), region(Point.Kind.ELSE, b, 1),
                region(Point.Kind.THEN, a, 1)), List.of());
            eq(2, p.skippedRegions());
            eq(1, p.table().size());
        });

        c.test("a line part belongs to the innermost region around an expression start; no region, no part", () -> {
            Span body = at("if (x < 3) x + 1 > 0 else false");
            Span elseB = at("false");
            int def2 = SRC.indexOf("-1");
            SitePlan p = plan(List.of(), List.of(region(Point.Kind.FUNCTION, body, 1), region(Point.Kind.ELSE, elseB, 1)),
                List.of(new Exec("/p/src/A.flix", body.start(), 1), new Exec("/p/src/A.flix", elseB.start(), 1),
                    new Exec("/p/src/A.flix", elseB.start(), 1), new Exec("/p/src/A.flix", def2, 2),
                    new Exec("/p/src/A.flix", 0, 1)));
            // FUNCTION 1, LINE (line 1, owner FUNCTION) 2, ELSE 3, LINE (line 1, owner ELSE) 4; line 2 and the start of the def have no region.
            eq(List.of(Point.Kind.FUNCTION, Point.Kind.LINE, Point.Kind.ELSE, Point.Kind.LINE),
                p.table().points().stream().map(Point::kind).toList());
            eq(List.of(2), p.lineParts(1));
            eq(List.of(4), p.lineParts(3));
            eq(List.of(), p.lineParts(2));
        });

        c.test("render: a region wraps its text, a site in it is rewritten inside, a region in an operand sits inside the call", () -> {
            Span body = at("if (x < 3) x + 1 > 0 else false");
            Span elseB = at("false");
            Span thenB = at("x + 1 > 0");
            SitePlan p = plan(List.of(less(), greater(), add()),
                List.of(region(Point.Kind.FUNCTION, body, 1), region(Point.Kind.THEN, thenB, 1), region(Point.Kind.ELSE, elseB, 1)), List.of());
            Schemata.Rendered r = Schemata.render(SRC, p.items(F));
            String m = "bug!(\"flixw-cov\")";
            eq("def f(x: Int32): Bool = (if (FlixwMutant.on(1)) " + m + " else { if ((FlixwMutant.less(2, 3, (x), (3)))) "
                + "(if (FlixwMutant.on(4)) " + m + " else { (FlixwMutant.greater(5, 6, ((FlixwMutant.add(7, (x), (1)))), (0))) }) "
                + "else (if (FlixwMutant.on(8)) " + m + " else { false }) })\ndef g(): Int32 = -1\n", r.text());
            eq(List.of(2, 3, 5, 6, 7), r.mutants().stream().map(Mutant::id).toList());
        });

        c.test("render: a region around a leading minus keeps parentheses (a brace and a minus read as a record restriction)", () -> {
            Span m1 = at("-1");
            Schemata.Rendered r = Schemata.render(SRC, List.of(Schemata.Item.ofRegion(m1, 4)));
            yes(r.text().contains("= (if (FlixwMutant.on(4)) bug!(\"flixw-cov\") else { (-1) })\n"), r.text());
        });

        c.test("render: a region that overlaps a region or a site badly is an error, a site in a site's gap too", () -> {
            Span a = at("(x < 3) x");
            Span b = at("x < 3) x + 1");
            throwsA(IllegalArgumentException.class, () ->
                Schemata.render(SRC, List.of(Schemata.Item.ofRegion(a, 1), Schemata.Item.ofRegion(b, 2))));
            throwsA(IllegalArgumentException.class, () ->
                Schemata.render(SRC, List.of(Schemata.Item.ofSite(less(), 1), Schemata.Item.ofRegion(new Span(at("x < 3").start() + 1, at("x < 3").start() + 3), 4))));
            // the old entry point renders the sites as before
            eq(SRC.replace("x < 3", "(FlixwMutant.less(1, 2, (x), (3)))"), Schemata.render(SRC, List.of(less()), 1).text());
        });

        c.test("balanced: brackets in strings, characters and comments do not count; a missing bracket is found", () -> {
            yes(RegionFinder.balanced("f(a, [b], { c })"), "plain");
            yes(RegionFinder.balanced("\"(\" + g(')') // )\n"), "string, char, line comment");
            yes(RegionFinder.balanced("a /* ( */ + b"), "block comment");
            yes(!RegionFinder.balanced("f(a"), "open");
            yes(!RegionFinder.balanced("a)"), "close first");
            yes(!RegionFinder.balanced("{ a /* }"), "unterminated comment");
        });

        c.finish();
    }
}
