package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.util.List;

public final class ReportTest {
    static Result result(int id, Outcome o, String detail, int selected, int executed, int full) {
        Site s = new Site("src/A.flix", SiteKind.LESS, new Span(0, 5), new Span(0, 1), new Span(4, 5));
        return new Result(new Mutant(id, s, "<", id % 2 == 0 ? ">=" : "<="), "src/A.flix", "3:" + id, o, detail,
            selected, executed, full);
    }

    public static void main(String[] args) {
        Check c = new Check("ReportTest");
        Report r = new Report(List.of(
            result(1, Outcome.KILLED, "testA: Assertion failed", 2, 1, 1),
            result(2, Outcome.SURVIVED, "", 3, 3, 5),
            result(3, Outcome.TIMEOUT, "", 1, 1, 5),
            result(4, Outcome.NOT_COVERED, "", 0, 0, 5),
            result(5, Outcome.KILLED, "testB: boom", 1, 1, 4)), 2, 5, 1200, 12000, null, true);

        c.test("counts per outcome", () -> {
            eq(2, r.count(Outcome.KILLED));
            eq(1, r.count(Outcome.SURVIVED));
            eq(1, r.count(Outcome.TIMEOUT));
            eq(1, r.count(Outcome.NOT_COVERED));
        });

        c.test("the score counts killed and timed out mutants of all, a mutant that no test reaches is not killed", () -> eq(60.0, r.score()));

        c.test("no mutants: score -1", () -> eq(-1.0, new Report(List.of(), 0, 3, 5, 100).score()));

        c.test("one line per mutant, a mutant no test reaches in its own line, then the summary", () -> {
            List<String> lines = r.format().lines().toList();
            eq("killed      src/A.flix:3:1  < -> <=  testA: Assertion failed", lines.get(0));
            eq("survived    src/A.flix:3:2  < -> >=", lines.get(1));
            eq("timeout     src/A.flix:3:3  < -> <=", lines.get(2));
            eq("not covered src/A.flix:3:4  < -> >=", lines.get(3));
            eq("killed      src/A.flix:3:5  < -> <=  testB: boom", lines.get(4));
            eq("", lines.get(5));
            eq("mutants: 5 (killed 2, survived 1, not covered 1, timeout 1); sites left out: 2", lines.get(6));
            eq("tests: 5 (baseline 1200 ms, timeout per mutant 12000 ms)", lines.get(7));
            eq("test selection: on; tests per mutant: mean 1.4, median 1, max 3 (0: 1, 1: 2, 2: 1, 3: 1)", lines.get(8));
            eq("test executions: 6 (a full run: 20, computed)", lines.get(9));
            eq("mutation score: 60.0% (killed or timed out, of all mutants)", lines.get(10));
            eq(11, lines.size());
        });

        c.test("selection off: no tests-per-mutant line, executions are measured", () -> {
            Report off = new Report(List.of(
                result(1, Outcome.KILLED, "testA: x", 5, 2, 2), result(2, Outcome.SURVIVED, "", 5, 5, 5)), 0, 5, 10, 2000, null, false);
            List<String> lines = off.format().lines().toList();
            eq("test selection: off", lines.get(5));
            eq("test executions: 7", lines.get(6));
        });

        c.test("many distinct counts: no distribution list", () -> {
            java.util.ArrayList<Result> many = new java.util.ArrayList<>();
            for (int i = 1; i <= 12; i++) many.add(result(i, Outcome.SURVIVED, "", i, i, 20));
            String line = new Report(many, 0, 20, 10, 2000, null, true).format().lines()
                .filter(l -> l.startsWith("test selection")).findFirst().orElseThrow();
            eq("test selection: on; tests per mutant: mean 6.5, median 6, max 12", line);
        });

        c.test("no mutants: the score line says so", () -> {
            String text = new Report(List.of(), 0, 3, 5, 100).format();
            yes(text.contains("mutants: 0 (killed 0, survived 0, not covered 0, timeout 0); sites left out: 0"), text);
            yes(text.contains("mutation score: n/a (no mutants)"), text);
        });

        c.finish();
    }
}
