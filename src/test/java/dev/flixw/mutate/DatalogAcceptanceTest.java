package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Full and selected Datalog runs through Session. External material is an explicit opt-in. */
public final class DatalogAcceptanceTest {
    private static Engine.Config config(Path project) {
        return new Engine.Config(project, EngineTest.JAVA, EngineTest.CP, 30000L)
            .withDatalogMutants().withoutOrder();
    }

    private static void compare(Path project, String label, boolean sample) throws Exception {
        try (Session selected = Session.open(config(project));
             Session full = Session.open(config(project).withoutSelection())) {
            List<Mutant> a = selected.mutants().stream().filter(m -> m.datalog() != null).toList();
            List<Mutant> b = full.mutants().stream().filter(m -> m.datalog() != null).toList();
            eq(a.size(), b.size());
            yes(!a.isEmpty(), "no Datalog mutants in " + project);
            Set<String> ops = new HashSet<>();
            int compared = 0;
            for (int i = 0; i < a.size(); i++) {
                DatalogMutation d = a.get(i).datalog();
                eq(d, b.get(i).datalog());
                if (sample && d.op().equals(DatalogMutation.FACT) && d.key().contains(";")) {
                    System.out.println(label + " unsupported lattice fact=" + d.key());
                    continue;
                }
                if (sample && !ops.add(d.op())) continue;
                ops.add(d.op());
                Result x = selected.run(a.get(i));
                Result y = full.run(b.get(i));
                System.out.printf("%s %s %s atom=%d selected=%d executed=%d full-executed=%d outcome=%s full=%s%n",
                    label, d.op(), d.key(), d.atom(), x.selected(), x.executed(), y.executed(),
                    x.outcome(), y.outcome());
                System.out.println("  tests=" + selected.coverage().testsFor(d.row()));
                System.out.println("  selected-detail=" + x.detail() + " full-detail=" + y.detail());
                yes(x.outcome() != Outcome.TIMEOUT && y.outcome() != Outcome.TIMEOUT, "timeout: " + d);
                eq(y.outcome(), x.outcome());
                compared++;
            }
            yes(compared > 0, "no mutants compared");
            Set<String> expectedOps = sample && label.equals("If")
                ? Set.of(DatalogMutation.RULE, DatalogMutation.ATOM)
                : Set.of(DatalogMutation.RULE, DatalogMutation.ATOM, DatalogMutation.FACT);
            eq(expectedOps, ops);
            if (sample && label.equals("If")) System.out.println("If: no supported fact sample (lattices excluded)");
            System.out.println("compared=" + compared + " operators=" + ops);
        }
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
