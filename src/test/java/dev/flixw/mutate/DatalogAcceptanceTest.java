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

    /** What the coverage that selects the tests is replaced by in a sabotage check. */
    private enum Mode { REAL, NO_MARKS, REACH_ALL }

    /** The Datalog mutants of a session with their results and the tests the (possibly sabotaged) coverage selects for each. */
    private record Run(List<Mutant> mutants, List<Result> results, List<List<String>> tests) { }

    /** Lattice facts ({@code ;} in the text) are not an operator of this stage; on the material they are listed and left out. */
    private static boolean lattice(DatalogMutation d) { return d.op().equals(DatalogMutation.FACT) && d.key().contains(";"); }

    private static Coverage sabotaged(Coverage real, Mode mode) {
        Hits.Builder b = new Hits.Builder(real.sites(), real.tests());
        if (mode == Mode.REACH_ALL)
            for (String t : real.tests()) for (int id = 1; id <= real.sites().size(); id++) b.reach(t, id);
        return new Coverage(real.sites(), b.build());
    }

    private static Run runAll(Session s, List<Mutant> mutants, Mode mode) throws Exception {
        Coverage real = s.coverage();
        if (mode != Mode.REAL) s.useCoverage(sabotaged(real, mode));
        try {
            List<Result> results = new ArrayList<>();
            List<List<String>> tests = new ArrayList<>();
            for (Mutant m : mutants) {
                tests.add(s.coverage().testsFor(m.datalog().row()));
                results.add(s.run(m));
            }
            return new Run(mutants, results, tests);
        } finally {
            s.useCoverage(real);
        }
    }

    /**
     * The tests that solve the rule or fact of each mutant, found without the coverage: each test of the baseline run alone under
     * the mutant, kept if the hook changed a constraint in one of its solves (any outcome but not covered).
     */
    private static List<List<String>> truth(Session s, List<Mutant> mutants) throws Exception {
        List<List<String>> out = new ArrayList<>();
        for (Mutant m : mutants) {
            List<String> t = new ArrayList<>();
            for (String test : s.coverage().tests()) {
                Result r = s.runDatalogOnly(m, List.of(test));
                yes(r.outcome() != Outcome.TIMEOUT, "timeout: " + m.datalog() + " " + test);
                if (r.outcome() != Outcome.NOT_COVERED) t.add(test);
            }
            out.add(t);
        }
        return out;
    }

    /**
     * The one deviation that is known and open (issue #28, see {@link #main}): several source rules with the same signature are one
     * mutant at the solver, so a test that makes the changed rule invalid and a test that makes it kill both belong to it, and the
     * outcome is that of the test that runs first; the order of the selected tests differs from that of the full run.
     */
    private static final String KNOWN = "known-order: ";

    /**
     * Over-selection of a fact mutant: a fact whose literals the solver prints differently from the source (typed literals such as
     * {@code 1i8}, characters, {@code ()}, constructors) matches by predicate name, so more tests are selected than solve it; they
     * run and the mutant reads not covered. This is the documented imprecision (it never loses a test); a rule is matched exactly.
     */
    private static final String OVER_FACT = "over-selected-fact: ";

    /** What a selected run gets wrong against the full run (outcome) and against the truth (tests). Empty: nothing. */
    private static List<String> deviations(Run selected, Run full, List<List<String>> truth) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < selected.mutants().size(); i++) {
            DatalogMutation d = selected.mutants().get(i).datalog();
            Outcome x = selected.results().get(i).outcome();
            Outcome y = full.results().get(i).outcome();
            long sharing = selected.mutants().stream().filter(m -> m.datalog().op().equals(d.op()) && m.datalog().key().equals(d.key())
                && m.datalog().atom() == d.atom()).count();
            boolean killedOrInvalid = x == Outcome.KILLED && y == Outcome.INVALID || x == Outcome.INVALID && y == Outcome.KILLED;
            if (x == Outcome.TIMEOUT || y == Outcome.TIMEOUT) out.add("timeout: " + d);
            else if (x != y && sharing > 1 && killedOrInvalid) out.add(KNOWN + d.op() + " " + d.key() + " " + d.atom() + " selected " + x + " full " + y);
            else if (x != y) out.add("outcome: " + d + " selected " + x + " full " + y);
            List<String> sel = selected.tests().get(i);
            // the session ran the tests that its coverage selects (a mutant run with fewer or more tests than the coverage names)
            if (selected.results().get(i).selected() != sel.size())
                out.add("ran-selection: " + d + " coverage " + sel.size() + " ran with " + selected.results().get(i).selected());
            List<String> tru = truth.get(i);
            if (!sel.containsAll(tru)) out.add("under-selected: " + d + " selected " + sel.size() + " truth " + tru.size());
            else if (!tru.containsAll(sel))
                out.add((d.op().equals(DatalogMutation.FACT) ? OVER_FACT : "over-selected: ") + d + " selected " + sel.size() + " truth " + tru.size());
        }
        return out;
    }

    private static boolean any(List<String> deviations, String kind) {
        return deviations.stream().anyMatch(d -> d.startsWith(kind));
    }

    private static void compare(Path project, String label, boolean material) throws Exception {
        long t0 = System.nanoTime();
        Run selected;
        List<List<String>> truth;
        Run noMarks;
        Run reachAll;
        int skipped = 0;
        int allTests;
        try (Session s = Session.open(config(project))) {
            List<Mutant> mutants = new ArrayList<>();
            for (Mutant m : s.mutants()) {
                if (m.datalog() == null) continue;
                if (material && lattice(m.datalog())) {
                    System.out.println(label + " unsupported lattice fact=" + m.datalog().key());
                    skipped++;
                } else mutants.add(m);
            }
            yes(!mutants.isEmpty(), "no Datalog mutants in " + project);
            allTests = s.coverage().tests().size();
            selected = runAll(s, mutants, Mode.REAL);
            truth = truth(s, mutants);
            noMarks = runAll(s, mutants, Mode.NO_MARKS);
            reachAll = runAll(s, mutants, Mode.REACH_ALL);
        }
        Run full;
        try (Session s = Session.open(config(project).withoutSelection())) {
            full = runAll(s, selected.mutants(), Mode.REAL);
        }
        Set<String> ops = new TreeSet<>();
        int notCovered = 0;
        for (int i = 0; i < selected.mutants().size(); i++) {
            DatalogMutation d = selected.mutants().get(i).datalog();
            Result x = selected.results().get(i);
            Result y = full.results().get(i);
            System.out.printf("%s %s %s atom=%d tests=%d executed=%d full-executed=%d outcome=%s full=%s%n",
                label, d.op(), d.key(), d.atom(), selected.tests().get(i).size(), x.executed(), y.executed(),
                x.outcome(), y.outcome());
            if (x.outcome() == Outcome.NOT_COVERED) notCovered++;
            ops.add(d.op());
        }
        // If has no supported fact: its facts are lattice facts
        eq(material && label.equals("If") ? Set.of(DatalogMutation.RULE, DatalogMutation.ATOM)
            : Set.of(DatalogMutation.RULE, DatalogMutation.ATOM, DatalogMutation.FACT), ops);
        List<String> real = deviations(selected, full, truth);
        for (String d : real) System.out.println(label + " DEVIATION " + d);
        eq(List.of(), real.stream().filter(d -> !d.startsWith(KNOWN) && !d.startsWith(OVER_FACT)).toList());
        // Sabotage: the same comparison must fail. No marks: no test reaches any mutant (a killed mutant reads not covered). Reach all:
        // every test reaches every mutant (the outcomes agree, the selected tests are not those that solve the rule or fact).
        List<String> bad = deviations(noMarks, full, truth);
        boolean someReached = truth.stream().anyMatch(t -> !t.isEmpty());
        yes(!someReached || any(bad, "outcome") && any(bad, "under-selected"), "no marks went unnoticed: " + bad);
        List<String> over = deviations(reachAll, full, truth);
        // a rule that some test does not solve: a reach-all coverage over-selects it (facts do not count, see OVER_FACT)
        boolean someUnreached = false;
        for (int i = 0; i < truth.size(); i++)
            if (!selected.mutants().get(i).datalog().op().equals(DatalogMutation.FACT) && truth.get(i).size() < allTests) someUnreached = true;
        yes(!someUnreached || any(over, "over-selected: "), "reach all went unnoticed: " + over);
        yes(!any(over, "outcome") && !any(over, "timeout"), "reach all changed an outcome: " + over);
        System.out.printf("SUMMARY %s: tests=%d mutants=%d equal=%d not-covered=%d lattice-skipped=%d operators=%s known-order=%d over-selected-facts=%d sabotage no-marks=%d reach-all=%d%s seconds=%d%n",
            label, allTests, selected.mutants().size(), selected.mutants().size() - real.stream().filter(d -> d.startsWith(KNOWN)).count(), notCovered, skipped, ops, real.stream().filter(d -> d.startsWith(KNOWN)).count(), real.stream().filter(d -> d.startsWith(OVER_FACT)).count(), bad.size(), over.size(),
            someUnreached ? "" : " (every mutant is reached by every test: reach all cannot differ)", (System.nanoTime() - t0) / 1_000_000_000L);
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("DatalogAcceptanceTest");
        c.test("fixture reachability matches the independently listed rules and facts", () -> {
            try (Session s = Session.open(config(DatalogHitsTest.FIXTURE))) {
                eq(List.of(), DatalogHitsTest.deviations(s.coverage()));
            }
        });
        c.test("fixture: selected and full outcomes agree for every Datalog mutant", () -> compare(DatalogHitsTest.FIXTURE, "fixture", false));
        c.test("known defect: the outcome of a mutant whose signature is shared by two rules depends on the order of the tests", () -> {
            Path dir = Check.tempDir("dl-shared-signature");
            Files.createDirectories(dir.resolve("src"));
            Files.createDirectories(dir.resolve("test"));
            Files.writeString(dir.resolve("src/M.flix"), """
                mod M {
                    pub def f(): Vector[Int32] =
                        let p = #{ B(2, 5). A(2, 7). C(x, y) :- B(2, y), A(2, x). };
                        let m = solve p;
                        query m select y from C(_, y)
                    pub def g(): Vector[Int32] =
                        let p = #{ B(1). B(2). A(1). C(x) :- B(x), A(x). };
                        let m = solve p;
                        query m select x from C(x)
                }
                """);
            Files.writeString(dir.resolve("test/TestM.flix"), """
                mod TestM {
                    use Assert.assertEq;
                    @Test
                    def testA(): Unit \\ Assert = assertEq(expected = Vector#{5}, M.f())
                    @Test
                    def testB(): Unit \\ Assert = assertEq(expected = Vector#{1}, M.g())
                }
                """);
            try (Session s = Session.open(config(dir))) {
                Mutant m = s.mutants().stream().filter(x -> x.datalog() != null && x.datalog().op().equals(DatalogMutation.ATOM)
                    && x.datalog().key().equals("C<-B,A") && x.datalog().atom() == 1).findFirst().orElseThrow();
                List<String> ab = List.of("TestM.testA", "TestM.testB");
                // without Datalog atom 1 testA's rule has an unbound variable (invalid), testB's rule gives another model (killed)
                eq(Outcome.INVALID, s.runDatalogOnly(m, ab).outcome());
                eq(Outcome.KILLED, s.runDatalogOnly(m, List.of("TestM.testB", "TestM.testA")).outcome());
            }
        });
        String material = System.getenv("FLIXW_DL_MATERIAL");
        if (material == null) {
            System.out.println("Datalog material: not run (opt-in)");
        } else {
            // FLIXW_DL_FILES (comma separated names) limits the run to some of the files, for diagnosis on a loaded machine
            String only = System.getenv("FLIXW_DL_FILES");
            for (String name : List.of("If", "PSolve", "Solve.Simple", "Solve.Project", "Stratification")) {
                if (only != null && !List.of(only.split(",")).contains(name)) continue;
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
