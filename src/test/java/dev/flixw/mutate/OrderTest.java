package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The order of the selected tests of a mutant, end to end: tests that ran fast and tests that killed earlier mutants
 * of the same run go first, so a killed mutant ends earlier. The outcome of every mutant stays that of the full run.
 */
public final class OrderTest {
    static final Path FIXTURES = Path.of(System.getenv("FLIXW_TEST_FIXTURES")).toAbsolutePath();
    static final Path ORDER = FIXTURES.resolve("order");
    static final Path LEARN = FIXTURES.resolve("order-learn");
    static final Path TWO = FIXTURES.resolve("order-two");

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
            // The first mutant has no history: name order, or the duration if a weak test is slow (all three tests are
            // trivial, so a slow first call decides). After it the killer goes first; one miss is allowed for timer noise.
            yes(executed.get(0) >= 2, executed.toString());
            yes(executed.subList(1, 4).stream().mapToInt(Integer::intValue).sum() <= 4, executed.toString());
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
            }
            yes(selected.format().contains("(a full run: at most "), selected.format());
        });

        c.finish();
    }
}
