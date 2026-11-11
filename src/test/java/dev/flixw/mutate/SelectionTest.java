package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Test selection against the full run: the same outcome for every mutant (a mutant no test reaches counts as
 * survived), no unselected test fails under a mutant, the recorded sites do not depend on the test order, and
 * a marker that never records is caught. Objects: the calc fixture (34 mutants, 11 tests) and the wide fixture
 * of the coverage spike (53 mutants, 22 tests; helpers that several tests reach, a nullary def, a lazy value,
 * a recursive loop, code no test calls, a hot loop).
 */
public final class SelectionTest {
    static final Path JAVA = EngineTest.JAVA;
    static final String CP = EngineTest.CP;
    static final Path CALC = SiteFinderTest.FIXTURE;
    static final Path WIDE = Path.of(System.getenv("FLIXW_TEST_WIDE")).toAbsolutePath();

    /** Outcome kind with the surviving labels merged, and the killing test: what a full run says about a mutant. */
    static String verdict(Result r) {
        Outcome o = r.outcome() == Outcome.NOT_COVERED ? Outcome.SURVIVED : r.outcome();
        return o + (o == Outcome.KILLED ? " " + r.detail() : "");
    }

    /** One line per mutant that differs between the two reports; empty if they agree. */
    static List<String> differences(Report full, Report selected) {
        eq(full.results().size(), selected.results().size());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < full.results().size(); i++) {
            Result a = full.results().get(i);
            Result b = selected.results().get(i);
            if (!verdict(a).equals(verdict(b)))
                out.add("mutant " + a.mutant().id() + " " + a.file() + ":" + a.position() + " full: " + verdict(a) + ", selected: "
                    + verdict(b) + " (" + b.outcome().label() + ")");
        }
        return out;
    }

    static Engine.Config config(Path root) { return new Engine.Config(root, JAVA, CP, null); }

    static String distribution(Report r) {
        TreeMap<Integer, Integer> h = new TreeMap<>();
        for (Result x : r.results()) h.merge(x.selected(), 1, Integer::sum);
        return h.toString();
    }

    /** Every (mutant, unselected test) pair run alone under the mutant must pass. Returns the number of pairs. */
    static int probeUnselected(Path root, List<String> failures) throws Exception {
        int pairs = 0;
        try (Session s = Session.open(config(root))) {
            for (Mutant m : s.mutants()) {
                List<String> selected = s.coverage().testsFor(m.id());
                for (String t : s.coverage().tests()) {
                    if (selected.contains(t)) continue;
                    pairs++;
                    Result r = s.run(m, List.of(t), true);
                    if (r.outcome() != Outcome.SURVIVED) failures.add("mutant " + m.id() + " test " + t + ": " + r.outcome());
                }
            }
        }
        return pairs;
    }

    /** A Marker that never records, in a class directory that goes first on the worker class path. */
    static Path saboteur() throws Exception {
        Path dir = Files.createTempDirectory("sabotage");
        Path src = dir.resolve("src/dev/flixw/mutate/Marker.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package dev.flixw.mutate;
            public final class Marker {
                private Marker() { }
                private static final int[] STATE = (int[]) System.getProperties().get("flixw.mutate.state");
                public static boolean on(int n) { return n == STATE[0]; }
            }
            """);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        eq(0, javac.run(null, null, null, "-d", classes.toString(), src.toString()));
        return classes;
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("SelectionTest");

        c.test("calc: the selected run and the full run give the same outcome for every mutant", () -> {
            Report full = Engine.run(config(CALC).withoutSelection());
            Report selected = Engine.run(config(CALC));
            eq(List.of(), differences(full, selected));
            eq(34, selected.results().size());
            eq(1, selected.count(Outcome.NOT_COVERED));
            eq(0, full.count(Outcome.NOT_COVERED));
            eq(full.count(Outcome.SURVIVED), selected.count(Outcome.SURVIVED) + selected.count(Outcome.NOT_COVERED));
            eq("{0=1, 1=33}", distribution(selected));
            eq("{11=34}", distribution(full));
        });

        c.test("calc: executions, measured for the full run and computed for the selected run, agree", () -> {
            Report full = Engine.run(config(CALC).withoutSelection());
            Report selected = Engine.run(config(CALC));
            long fullMeasured = full.results().stream().mapToLong(Result::executed).sum();
            long fullComputed = selected.results().stream().mapToLong(Result::fullExecuted).sum();
            long chosen = selected.results().stream().mapToLong(Result::executed).sum();
            // The timeout mutant has no measured count: both sides count all its tests.
            eq(fullMeasured, fullComputed);
            yes(chosen * 5 < fullMeasured, "selected " + chosen + " against full " + fullMeasured);
        });

        c.test("instances: the selected run and the full run give the same outcome for every mutant", () -> {
            Report full = Engine.run(config(SiteFinderTest.INSTANCES).withoutSelection());
            Report selected = Engine.run(config(SiteFinderTest.INSTANCES));
            eq(List.of(), differences(full, selected));
            eq(9, selected.results().size());
            eq(0, selected.count(Outcome.NOT_COVERED));
        });

        c.test("wide: the selected run and the full run give the same outcome for every mutant", () -> {
            Report full = Engine.run(config(WIDE).withoutSelection());
            Report selected = Engine.run(config(WIDE));
            eq(List.of(), differences(full, selected));
            eq(53, selected.results().size());
            eq(4, selected.count(Outcome.NOT_COVERED));
            eq("{0=4, 1=43, 2=4, 3=1, 4=1}", distribution(selected));
        });

        c.test("calc: no unselected test fails under a mutant", () -> {
            List<String> failures = new ArrayList<>();
            int pairs = probeUnselected(CALC, failures);
            eq(List.of(), failures);
            eq(341, pairs);
        });

        c.test("wide: no unselected test fails under a mutant", () -> {
            List<String> failures = new ArrayList<>();
            int pairs = probeUnselected(WIDE, failures);
            eq(List.of(), failures);
            eq(1108, pairs);
        });

        c.test("the sites a test evaluates do not depend on the test order (fresh program, reverse order)", () -> {
            for (Path root : List.of(CALC, WIDE)) {
                try (Session s = Session.open(config(root))) {
                    List<String> reverse = new ArrayList<>(s.coverage().tests());
                    Collections.reverse(reverse);
                    eq(s.coverage(), s.freshBaseline(reverse));
                }
            }
        });

        c.test("sabotage: a marker that never records selects nothing, and the comparison fails on the killed mutants", () -> {
            Report full = Engine.run(config(CALC).withoutSelection());
            Engine.Config sabotaged = new Engine.Config(CALC, JAVA, saboteur() + java.io.File.pathSeparator + CP, null);
            Report selected = Engine.run(sabotaged);
            eq(34, selected.count(Outcome.NOT_COVERED));
            List<String> diff = differences(full, selected);
            eq(full.count(Outcome.KILLED) + full.count(Outcome.TIMEOUT), diff.size());
            yes(diff.get(0).contains("full: KILLED") && diff.get(0).contains("selected: SURVIVED (not covered)"), diff.get(0));
        });

        c.finish();
    }
}
