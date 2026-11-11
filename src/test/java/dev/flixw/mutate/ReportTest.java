package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.util.List;

public final class ReportTest {
    static Result result(int id, Outcome o, String detail) {
        Site s = new Site("src/A.flix", SiteKind.LESS, new Span(0, 5), new Span(0, 1), new Span(4, 5));
        return new Result(new Mutant(id, s, "<", id % 2 == 0 ? ">=" : "<="), "src/A.flix", "3:" + id, o, detail);
    }

    public static void main(String[] args) {
        Check c = new Check("ReportTest");
        Report r = new Report(List.of(
            result(1, Outcome.KILLED, "testA: Assertion failed"),
            result(2, Outcome.SURVIVED, ""),
            result(3, Outcome.TIMEOUT, ""),
            result(4, Outcome.KILLED, "testB: boom")), 2, 5, 1200, 12000);

        c.test("counts per outcome", () -> {
            eq(2, r.count(Outcome.KILLED));
            eq(1, r.count(Outcome.SURVIVED));
            eq(1, r.count(Outcome.TIMEOUT));
        });

        c.test("the score counts killed and timed out mutants", () -> eq(75.0, r.score()));

        c.test("no mutants: score -1", () -> eq(-1.0, new Report(List.of(), 0, 3, 5, 100).score()));

        c.test("one line per mutant, then the summary", () -> {
            List<String> lines = r.format().lines().toList();
            eq("killed    src/A.flix:3:1  < -> <=  testA: Assertion failed", lines.get(0));
            eq("survived  src/A.flix:3:2  < -> >=", lines.get(1));
            eq("timeout   src/A.flix:3:3  < -> <=", lines.get(2));
            eq("killed    src/A.flix:3:4  < -> >=  testB: boom", lines.get(3));
            eq("", lines.get(4));
            eq("mutants: 4 (killed 2, survived 1, timeout 1); sites left out: 2", lines.get(5));
            eq("tests: 5 (baseline 1200 ms, timeout per mutant 12000 ms)", lines.get(6));
            eq("mutation score: 75.0% (killed or timed out, of all mutants)", lines.get(7));
            eq(8, lines.size());
        });

        c.test("no mutants: the score line says so", () -> {
            String text = new Report(List.of(), 0, 3, 5, 100).format();
            yes(text.contains("mutants: 0 (killed 0, survived 0, timeout 0); sites left out: 0"), text);
            yes(text.contains("mutation score: n/a (no mutants)"), text);
        });

        c.finish();
    }
}
