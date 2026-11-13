package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Datalog mutants at the solver (drop a rule, drop a body atom, drop a fact). Truth 1: the planted mutants of the
 * solver-hook spike ({@code spikes/solver-hook/mutants.tsv}, expected outcome per mutant): the nine drop-rule and
 * drop-atom ones are compared (the three negation flips are not an operator of this version). Truth 2: the Datalog
 * fixture of the coverage spike, whose rules and facts are known from {@link DatalogHitsTest}.
 */
public final class DatalogMutantsTest {
    static final Path SPIKE = Path.of(System.getenv("FLIXW_TEST_SOLVER_SPIKE")).toAbsolutePath();

    static Engine.Config config(Path project) { return new Engine.Config(project, EngineTest.JAVA, EngineTest.CP, null).withDatalogMutants(); }

    /** The result of every Datalog mutant of a run, with the mutant. */
    static List<Result> run(Engine.Config config) throws Exception {
        List<Result> out = new ArrayList<>();
        try (Session s = Session.open(config)) {
            for (Mutant m : s.mutants()) if (m.datalog() != null) out.add(s.run(m));
        }
        return out;
    }

    static Result find(List<Result> results, String op, String key, int atom) {
        for (Result r : results) {
            DatalogMutation d = r.mutant().datalog();
            if (d.op().equals(op) && d.key().equals(key) && d.atom() == atom) return r;
        }
        throw new AssertionError("no mutant " + op + " " + key + " " + atom);
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("DatalogMutantsTest");
        List<Result> spike = new ArrayList<>();

        c.test("the spike fixture: the planted drop-rule and drop-atom mutants have the planted outcome (9 of 12)", () -> {
            spike.addAll(run(config(SPIKE.resolve("fixture"))));
            // the rules in source order are the rule numbers of mutants.tsv
            List<String> rules = new ArrayList<>();
            for (Result r : spike) if (r.mutant().datalog().op().equals(DatalogMutation.RULE) && !rules.contains(r.mutant().datalog().key())) rules.add(r.mutant().datalog().key());
            eq(List.of("Reach<-Start", "Reach<-Reach,Edge", "Unreach<-Node,Reach", "Reach<-Reach,Edge,Node", "Pair<-Edge,Reach,Reach", "Seen<-Reach"), rules);
            int compared = 0;
            List<String> rows = Files.readAllLines(SPIKE.resolve("mutants.tsv"));
            for (String row : rows.subList(1, rows.size())) {
                String[] f = row.split("\t");
                if (!f[1].equals("drop-rule") && !f[1].equals("drop-atom")) continue;
                String key = rules.get(Integer.parseInt(f[2]));
                Result r = f[1].equals("drop-rule") ? find(spike, DatalogMutation.RULE, key, 0) : find(spike, DatalogMutation.ATOM, key, Integer.parseInt(f[3]));
                eq(f[4], r.outcome().label());
                compared++;
            }
            eq(9, compared);
        });

        c.test("every rule gives a drop-rule mutant and one per body atom, every fact a drop-fact mutant", () -> {
            if (spike.isEmpty()) spike.addAll(run(config(SPIKE.resolve("fixture"))));
            long rule = spike.stream().filter(r -> r.mutant().datalog().op().equals(DatalogMutation.RULE)).count();
            long atom = spike.stream().filter(r -> r.mutant().datalog().op().equals(DatalogMutation.ATOM)).count();
            long fact = spike.stream().filter(r -> r.mutant().datalog().op().equals(DatalogMutation.FACT)).count();
            eq(6L, rule);
            eq(12L, atom); // 1 + 2 + 2 + 3 + 3 + 1 body atoms
            eq(1L, fact);
        });

        List<Result> net = new ArrayList<>();
        c.test("the coverage spike's fixture: killed, survived, equivalent, invalid and not covered each where the program says", () -> {
            net.addAll(run(config(DatalogHitsTest.FIXTURE)));
            // facts: Edge(1,2) and Edge(2,3) and Start(1) are read by testReachable, Edge(7,8) and Tag(5) change a model no test reads
            eq(Outcome.KILLED, find(net, "fact", "Edge(1,2)", 0).outcome());
            eq(Outcome.KILLED, find(net, "fact", "Start(1)", 0).outcome());
            eq(Outcome.SURVIVED, find(net, "fact", "Edge(7,8)", 0).outcome());
            eq(Outcome.SURVIVED, find(net, "fact", "Tag(5)", 0).outcome());
            // Dead(x) :- Reach(x), Edge(x, 99) derives nothing: dropping it or its first atom leaves the model as it is
            eq(Outcome.EQUIVALENT, find(net, "rule", "Dead<-Reach,Edge", 0).outcome());
            eq(Outcome.EQUIVALENT, find(net, "atom", "Dead<-Reach,Edge", 0).outcome());
            // Reach(y) :- Reach(x), Edge(x, y) without Edge(x, y): y is unbound, the solver throws
            eq(Outcome.INVALID, find(net, "atom", "Reach<-Reach,Edge", 1).outcome());
            eq(Outcome.KILLED, find(net, "rule", "Reach<-Start", 0).outcome());
        });

        c.test("an invalid mutant is not counted as killed, an equivalent one is left out of the score", () -> {
            if (net.isEmpty()) net.addAll(run(config(DatalogHitsTest.FIXTURE)));
            Result inv = find(net, "atom", "Reach<-Reach,Edge", 1);
            yes(inv.detail().startsWith("TestNet.testReachable: "), inv.detail());
            Report r = new Report(net, 0, 3, 100, 1000);
            long killed = net.stream().filter(x -> x.outcome() == Outcome.KILLED).count();
            long invalid = net.stream().filter(x -> x.outcome() == Outcome.INVALID).count();
            long equivalent = net.stream().filter(x -> x.outcome() == Outcome.EQUIVALENT).count();
            yes(invalid > 0 && equivalent > 0, "fixture has no invalid or equivalent mutant");
            eq(r.count(Outcome.KILLED), (int) killed);
            eq(100.0 * killed / (net.size() - invalid - equivalent), r.score());
            yes(r.format().contains(", invalid " + invalid + ", equivalent " + equivalent + ")"), r.format());
        });

        c.test("the rule of a query has no mutant (a `$Result` rule is plumbing of the query)", () -> {
            for (Result r : net) yes(!r.mutant().datalog().key().startsWith("$Result"), r.mutant().datalog().key());
        });

        c.test("a fact that the solver prints differently from the source is reached by name but never changed: not covered, with the reason", () -> {
            Path dir = Files.createTempDirectory("dl-unmatched");
            Files.createDirectories(dir.resolve("src"));
            Files.createDirectories(dir.resolve("test"));
            Files.writeString(dir.resolve("src/M.flix"), """
                mod M {
                    pub def f(): Vector[Int32] =
                        let p = #{
                            P(1 + 1).
                            P(3).
                            Q(x) :- P(x).
                        };
                        query p select x from Q(x)
                }
                """);
            Files.writeString(dir.resolve("test/TestM.flix"), """
                mod TestM {
                    use Assert.assertEq;
                    @Test
                    def testF(): Unit \\ Assert = assertEq(expected = Vector#{2, 3}, M.f())
                }
                """);
            List<Result> r = run(config(dir));
            eq(Outcome.NOT_COVERED, find(r, "fact", "P(1+1)", 0).outcome());
            eq("no solve of the tests contains it by name", find(r, "fact", "P(1+1)", 0).detail());
            eq(Outcome.KILLED, find(r, "fact", "P(3)", 0).outcome());
            eq(Outcome.INVALID, find(r, "atom", "Q<-P", 0).outcome());
        });

        c.test("without the switch there is no Datalog mutant, and with only withDatalog the run is the run of the hits", () -> {
            try (Session s = Session.open(new Engine.Config(DatalogHitsTest.FIXTURE, EngineTest.JAVA, EngineTest.CP, null).withDatalog())) {
                for (Mutant m : s.mutants()) yes(m.datalog() == null, "a Datalog mutant without the switch");
            }
        });

        c.test("the test order (the default) does not change the outcome of any Datalog mutant", () -> {
            Engine.Config ordered = config(SPIKE.resolve("fixture"));
            yes(ordered.order(), "the order is on by default");
            List<Result> in = run(ordered);
            List<Result> byName = run(ordered.withoutOrder());
            eq(in.size(), byName.size());
            for (int i = 0; i < in.size(); i++) {
                eq(in.get(i).mutant().datalog(), byName.get(i).mutant().datalog());
                eq(byName.get(i).outcome(), in.get(i).outcome());
            }
            // and both are the planted outcomes of the first test
            eq(spike.stream().map(Result::outcome).toList(), in.stream().map(Result::outcome).toList());
        });

        c.test("the same outcomes without test selection", () -> {
            List<Result> all = run(config(SPIKE.resolve("fixture")).withoutSelection());
            eq(spike.size(), all.size());
            for (int i = 0; i < all.size(); i++) {
                Outcome with = spike.get(i).outcome();
                Outcome without = all.get(i).outcome();
                // a mutant that no test reaches is not covered with selection and runs every test without it
                if (with != Outcome.NOT_COVERED) eq(with, without);
            }
        });

        c.finish();
    }
}
