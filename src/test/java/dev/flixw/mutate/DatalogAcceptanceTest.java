package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Full and selected Datalog runs through Session. External material is an explicit opt-in. */
public final class DatalogAcceptanceTest {
    private static Engine.Config config(Path project) {
        return new Engine.Config(project, EngineTest.JAVA, EngineTest.CP, 30000L)
            .withDatalogMutants();
    }

    /** The Datalog mutants of a session with their results, and the tests the coverage selects for each. */
    private record Run(List<Mutant> mutants, List<Result> results, List<List<String>> tests) { }

    /** One session at a time (a second worker JVM next to the first is killed on a machine with 8 GB). */
    private static Run run(Engine.Config config) throws Exception {
        try (Session s = Session.open(config)) {
            List<Mutant> mutants = s.mutants().stream().filter(m -> m.datalog() != null).toList();
            List<Result> results = new ArrayList<>();
            List<List<String>> tests = new ArrayList<>();
            for (Mutant m : mutants) {
                tests.add(s.coverage().testsFor(m.datalog().row()));
                results.add(s.run(m));
            }
            return new Run(mutants, results, tests);
        }
    }

    /** Lattice facts ({@code ;} in the text) are not an operator of this stage; on the material they are listed and left out. */
    private static boolean lattice(DatalogMutation d) { return d.op().equals(DatalogMutation.FACT) && d.key().contains(";"); }

    private static void compare(Path project, String label, boolean material) throws Exception {
        long t0 = System.nanoTime();
        Run selected = run(config(project));
        Run full = run(config(project).withoutSelection());
        eq(selected.mutants().size(), full.mutants().size());
        yes(!selected.mutants().isEmpty(), "no Datalog mutants in " + project);
        Set<String> ops = new TreeSet<>();
        int compared = 0, notCovered = 0, skipped = 0;
        for (int i = 0; i < selected.mutants().size(); i++) {
            DatalogMutation d = selected.mutants().get(i).datalog();
            eq(d, full.mutants().get(i).datalog());
            if (material && lattice(d)) {
                System.out.println(label + " unsupported lattice fact=" + d.key());
                skipped++;
                continue;
            }
            Result x = selected.results().get(i);
            Result y = full.results().get(i);
            System.out.printf("%s %s %s atom=%d tests=%d executed=%d full-executed=%d outcome=%s full=%s%n",
                label, d.op(), d.key(), d.atom(), selected.tests().get(i).size(), x.executed(), y.executed(),
                x.outcome(), y.outcome());
            yes(x.outcome() != Outcome.TIMEOUT && y.outcome() != Outcome.TIMEOUT, "timeout: " + d);
            eq(y.outcome(), x.outcome());
            if (x.outcome() == Outcome.NOT_COVERED) notCovered++;
            ops.add(d.op());
            compared++;
        }
        yes(compared > 0, "no mutants compared");
        // If has no supported fact: its facts are lattice facts
        eq(material && label.equals("If") ? Set.of(DatalogMutation.RULE, DatalogMutation.ATOM)
            : Set.of(DatalogMutation.RULE, DatalogMutation.ATOM, DatalogMutation.FACT), ops);
        System.out.printf("SUMMARY %s: mutants=%d equal=%d not-covered=%d lattice-skipped=%d operators=%s seconds=%d%n",
            label, selected.mutants().size(), compared, notCovered, skipped, ops, (System.nanoTime() - t0) / 1_000_000_000L);
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("DatalogAcceptanceTest");
        c.test("fixture reachability matches the independently listed rules and facts", () -> {
            try (Session s = Session.open(config(DatalogHitsTest.FIXTURE))) {
                eq(List.of(), DatalogHitsTest.deviations(s.coverage()));
            }
        });
        c.test("fixture: selected and full outcomes agree for every Datalog mutant", () -> compare(DatalogHitsTest.FIXTURE, "fixture", false));
        String material = System.getenv("FLIXW_DL_MATERIAL");
        if (material == null) {
            System.out.println("Datalog material: not run (opt-in)");
        } else {
            for (String name : List.of("If", "PSolve", "Solve.Simple", "Solve.Project", "Stratification")) {
                c.test("material " + name + ": selected and full outcomes", () -> {
                    Path root = Check.tempDir("datalog-material-");
                    Path src = Files.createDirectories(root.resolve("src"));
                    Files.copy(Path.of(material, "Test.Exp.Fixpoint." + name + ".flix"), src.resolve("Material.flix"));
                    compare(root, name, true);
                });
            }
        }
        c.finish();
    }
}
