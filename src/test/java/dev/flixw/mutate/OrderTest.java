package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The order of the selected tests of a mutant, end to end: tests that ran fast and tests that killed earlier mutants
 * of the same run go first, so a killed mutant ends earlier. Without a hanging test the outcome of every mutant is that of
 * the full run; a mutant that one test fails and another hangs on is killed or timed out by whichever runs first.
 */
public final class OrderTest {
    static final Path FIXTURES = Path.of(System.getenv("FLIXW_TEST_FIXTURES")).toAbsolutePath();
    static final Path ORDER = FIXTURES.resolve("order");
    static final Path LEARN = FIXTURES.resolve("order-learn");
    static final Path TWO = FIXTURES.resolve("order-two");
    static final Path BOUND = FIXTURES.resolve("order-bound");
    static final Path FLIP = FIXTURES.resolve("order-flip");
    static final Path FLIP3 = FIXTURES.resolve("order-flip3");
    static final Path DL = FIXTURES.resolve("order-datalog");

    static Engine.Config config(Path root) { return new Engine.Config(root, EngineTest.JAVA, EngineTest.CP, null); }

    /** Outcome per mutant; the killing test is left out because a different order can end on a different failing test. */
    static List<String> outcomes(Report r) {
        List<String> out = new ArrayList<>();
        for (Result x : r.results()) out.add(x.mutant().id() + " " + x.file() + ":" + x.position() + " " + x.outcome());
        return out;
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("OrderTest");

        c.test("order: the fast killer runs first, the slow tests that cannot kill never run", () -> {
            Report selected = Engine.run(config(ORDER));
            eq(List.of(), selected.results().stream().filter(r -> r.outcome() != Outcome.KILLED).toList());
            yes(!selected.results().isEmpty(), "no mutants");
            for (Result r : selected.results()) {
                eq(3, r.selected());
                eq(1, r.executed());
                yes(r.detail().startsWith("TestBump.testB3Fast"), r.detail());
            }
        });

        c.test("order: the outcome of every mutant is that of the full run", () -> {
            Report full = Engine.run(config(ORDER).withoutSelection());
            Report selected = Engine.run(config(ORDER));
            eq(outcomes(full), outcomes(selected));
            // The full run starts with the slow tests; the ordered run does not.
            yes(selected.results().stream().mapToInt(Result::executed).sum()
                < full.results().stream().mapToInt(Result::executed).sum(), "no test saved");
        });

        c.test("learn: a test that killed earlier mutants of the run goes first", () -> {
            Report full = Engine.run(config(LEARN).withoutSelection());
            Report selected = Engine.run(config(LEARN));
            eq(outcomes(full), outcomes(selected));
            eq(4, selected.results().size());
            List<Integer> executed = selected.results().stream().map(Result::executed).toList();
            // After the first mutant the killer goes first (the exact order is checked against a model below).
            yes(executed.subList(1, 4).contains(1), executed.toString());
        });

        c.test("the run follows a model of the order: durations of the baseline, history of the earlier mutants", () -> {
            for (Path root : List.of(LEARN, ORDER, TWO)) {
                try (Session s = Session.open(config(root))) {
                    TestOrder model = new TestOrder(s.testNanos());
                    for (Mutant m : s.mutants()) {
                        List<String> sent = model.order(s.coverage().testsFor(m.id()));
                        Result r = s.run(m);
                        int expected = r.outcome() == Outcome.KILLED
                            ? sent.indexOf(r.detail().substring(0, r.detail().indexOf(": "))) + 1 : sent.size();
                        eq(expected, r.executed());
                        model.record(sent, r);
                    }
                }
            }
        });

        c.test("flip: a slow test hangs and a fast test fails under every mutant: the full run times out, the ordered run kills", () -> {
            Engine.Config base = new Engine.Config(FLIP, EngineTest.JAVA, EngineTest.CP, 2000L);
            Report full = Engine.run(base.withoutSelection());
            Report ordered = Engine.run(base);
            Report unordered = Engine.run(base.withoutOrder());
            yes(!full.results().isEmpty(), "no mutants");
            eq(full.results().size(), full.count(Outcome.TIMEOUT));
            eq(ordered.results().size(), ordered.count(Outcome.KILLED));
            // Switched off, the outcome of every mutant is that of the full run.
            eq(outcomes(full), outcomes(unordered));
            // Killed and timed out count alike in the score.
            eq(full.score(), ordered.score());
        });

        c.test("two: the computed full run is an upper bound of the measured one and the report says so", () -> {
            Report full = Engine.run(config(TWO).withoutSelection());
            Report selected = Engine.run(config(TWO));
            eq(outcomes(full), outcomes(selected));
            for (int i = 0; i < full.results().size(); i++) {
                // Every test kills every mutant: the full run ends on the first test (1), the ordered run on the fast
                // test, whose name-order position (2) is only an upper bound.
                eq(1, full.results().get(i).executed());
                yes(selected.results().get(i).fullExecuted() >= full.results().get(i).executed(), "bound");
                // The slow test is first in name order and not run: it might hang or fail, so the bound is all three
                // tests. Without that rule it would be the killer's position (2).
                eq(3, selected.results().get(i).fullExecuted());
            }
            yes(selected.format().contains("(a full run: at most "), selected.format());
        });

        c.test("bound: the killer is first in name order and the only test that ran: the computed full run is exact", () -> {
            Report full = Engine.run(config(BOUND).withoutSelection());
            Report selected = Engine.run(config(BOUND));
            eq(outcomes(full), outcomes(selected));
            yes(!full.results().isEmpty(), "no mutants");
            for (int i = 0; i < full.results().size(); i++) {
                // The selected run ends on the fast test (1 run of 2 selected); the unrun test is after the killer, so the
                // bound is the killer's position (1) and equals the measured full run.
                eq(1, selected.results().get(i).executed());
                eq(1, full.results().get(i).executed());
                eq(1, selected.results().get(i).fullExecuted());
            }
        });

        c.test("flip3: the full run times out and counts all tests; the computed full run of the ordered run is not lower", () -> {
            Engine.Config base = new Engine.Config(FLIP3, EngineTest.JAVA, EngineTest.CP, 2000L);
            Report full = Engine.run(base.withoutSelection());
            Report ordered = Engine.run(base);
            yes(!full.results().isEmpty(), "no mutants");
            eq(full.results().size(), full.count(Outcome.TIMEOUT));
            eq(ordered.results().size(), ordered.count(Outcome.KILLED));
            for (int i = 0; i < full.results().size(); i++) {
                eq(3, full.results().get(i).executed());
                yes(ordered.results().get(i).fullExecuted() >= full.results().get(i).executed(),
                    "computed " + ordered.results().get(i).fullExecuted() + " < measured " + full.results().get(i).executed());
            }
        });

        c.test("datalog hits and the order work in one run: the solving test reaches the rules, the fast killer goes first", () -> {
            Engine.Config config = new Engine.Config(DL, EngineTest.JAVA, EngineTest.CP, null).withDatalog();
            try (Session s = Session.open(config)) {
                Coverage cov = s.coverage();
                eq(List.of(), s.mutants().stream().filter(m -> cov.testsFor(m.id()).size() != 2).toList());
                long rulesOfSolver = cov.sites().points().stream().filter(p -> p.kind() == Point.Kind.RULE
                    && cov.hits().level("TestDlOrder.testB2Reach", p.id()) == Hits.REACHED).count();
                long rulesOfOthers = cov.sites().points().stream().filter(p -> p.kind() == Point.Kind.RULE
                    && (cov.hits().level("TestDlOrder.testA1Slow", p.id()) == Hits.REACHED
                        || cov.hits().level("TestDlOrder.testB3Double", p.id()) == Hits.REACHED)).count();
                eq(2L, rulesOfSolver);
                eq(0L, rulesOfOthers);
                yes(s.testNanos().containsKey("TestDlOrder.testA1Slow"), s.testNanos().toString());
                yes(!s.mutants().isEmpty(), "no mutants");
                for (Mutant m : s.mutants()) {
                    Result r = s.run(m);
                    eq(Outcome.KILLED, r.outcome());
                    eq(1, r.executed());
                    yes(r.detail().startsWith("TestDlOrder.testB3Double"), r.detail());
                }
            }
        });

        c.finish();
    }
}
