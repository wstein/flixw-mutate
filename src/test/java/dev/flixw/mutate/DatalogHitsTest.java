package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Datalog rules and facts per test, through the engine's worker (level reached): the Datalog fixture of the
 * coverage spike (#22) is the truth. Its files {@code expected-rules.tsv} (test, rule, tuples derived, source line)
 * and {@code expected-facts.tsv} (test, solve, facts) give, at the level reached, the rule lines and the fact count
 * per test and the one test without a solve. The columns for tuples derived and the relation sizes
 * ({@code expected-relations.tsv}) are higher levels and are not compared here.
 */
public final class DatalogHitsTest {
    static final Path FIXTURE = CoverageSitesTest.SPIKE.resolve("datalog-fixture");

    static Engine.Config config() { return new Engine.Config(FIXTURE, EngineTest.JAVA, EngineTest.CP, null).withDatalog(); }

    private static List<String[]> rows(String name) throws Exception {
        List<String> all = Files.readAllLines(FIXTURE.resolve(name));
        List<String[]> out = new ArrayList<>();
        for (String l : all.subList(1, all.size())) out.add(l.split("\t"));
        return out;
    }

    /**
     * The deviations of a coverage from the truth files: the rule lines each test reaches, the number of facts each
     * test reaches, and the tests that reach no rule and no fact. Empty if the coverage is the truth.
     */
    static List<String> deviations(Coverage cov) throws Exception {
        List<String> out = new ArrayList<>();
        List<String> wantRules = new ArrayList<>();
        for (String[] r : rows("expected-rules.tsv")) wantRules.add(r[0] + "\t" + r[3]);
        List<String> gotRules = CoverageSitesTest.reached(cov, Point.Kind.RULE);
        if (!CoverageSitesTest.sorted(wantRules).equals(gotRules)) out.add("rules: expected " + CoverageSitesTest.sorted(wantRules) + " got " + gotRules);
        Map<String, Integer> wantFacts = new TreeMap<>();
        for (String[] r : rows("expected-facts.tsv")) wantFacts.put(r[0], Integer.parseInt(r[2]));
        Map<String, Integer> gotFacts = new TreeMap<>();
        for (String r : Views.reached(cov.sites(), cov.hits(), Point.Kind.FACT)) gotFacts.merge(r.split("\t")[0], 1, Integer::sum);
        if (!wantFacts.equals(gotFacts)) out.add("facts: expected " + wantFacts + " got " + gotFacts);
        List<String> none = new ArrayList<>();
        for (String t : cov.tests()) {
            boolean any = false;
            for (Point p : cov.sites().points())
                if ((p.kind() == Point.Kind.RULE || p.kind() == Point.Kind.FACT) && cov.hits().level(t, p.id()) == Hits.REACHED) any = true;
            if (!any) none.add(t);
        }
        if (!List.of("TestNet.testDouble").equals(none)) out.add("tests without a solve: expected [TestNet.testDouble] got " + none);
        return out;
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("DatalogHitsTest");

        c.test("the fixture: rules, facts and the test without a solve are the truth of the spike", () -> {
            try (Session s = Session.open(config())) {
                eq(List.of(), deviations(s.coverage()));
            }
        });

        c.test("the table has one row per rule and fact of the sources, all at level reached", () -> {
            try (Session s = Session.open(config())) {
                SiteTable t = s.coverage().sites();
                long rules = t.points().stream().filter(p -> p.kind() == Point.Kind.RULE).count();
                long facts = t.points().stream().filter(p -> p.kind() == Point.Kind.FACT).count();
                // 4 + 1 rules and 2 query rules (one is not solved by the program, see the report), 5 + 3 facts
                eq(7L, rules);
                eq(8L, facts);
                for (Point p : t.points())
                    if (p.kind() == Point.Kind.RULE || p.kind() == Point.Kind.FACT) eq("src/DlNet.flix", p.file());
                for (String test : s.coverage().tests())
                    for (Point p : t.points()) yes(s.coverage().hits().level(test, p.id()) != Hits.FIRED, "level fired is not recorded");
            }
        });

        c.test("the report names the Datalog sites that no test reaches as not covered", () -> {
            Report r = Engine.run(config());
            String text = r.format();
            yes(text.contains("datalog sites: rules 7, facts 8, not covered 1"), text);
            yes(text.contains("not covered src/DlNet.flix:15  rule"), text);
        });

        c.test("without the switch the table has no Datalog rows and the report no Datalog line", () -> {
            Engine.Config off = new Engine.Config(FIXTURE, EngineTest.JAVA, EngineTest.CP, null);
            try (Session s = Session.open(off)) {
                for (Point p : s.coverage().sites().points()) yes(p.kind() == Point.Kind.MUTANT, "row " + p);
            }
            yes(!Engine.run(off).format().contains("datalog"), "datalog in the report");
        });

        c.finish();
    }
}
