package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** What the solves of a test reached, by rule signature and fact text. */
public final class DatalogMapTest {
    static DatalogSite rule(String def, String sig) { return new DatalogSite(Point.Kind.RULE, "/p/A.flix", new Span(0, 1), 1, def, sig); }

    static DatalogSite fact(String def, String key) { return new DatalogSite(Point.Kind.FACT, "/p/A.flix", new Span(0, 1), 1, def, key); }

    static Map<Integer, DatalogSite> sites(DatalogSite... all) {
        Map<Integer, DatalogSite> m = new TreeMap<>();
        for (int i = 0; i < all.length; i++) m.put(i + 1, all[i]);
        return m;
    }

    static TreeSet<Integer> set(Integer... ids) { return new TreeSet<>(List.of(ids)); }

    public static void main(String[] args) {
        Check c = new Check("DatalogMapTest");

        c.test("a rule is reached by its signature", () -> {
            Map<Integer, DatalogSite> s = sites(rule("M.a", "R<-S"), rule("M.a", "T<-R"), fact("M.a", "S(1)"));
            eq(set(2), DatalogMap.reached(s, "M.t", List.of("T<-R"), List.of()));
            eq(set(1, 2), DatalogMap.reached(s, "M.t", List.of("T<-R", "R<-S"), List.of()));
            eq(set(), DatalogMap.reached(s, "M.t", List.of("Other<-R"), List.of()));
        });

        c.test("a rule signature does not reach a fact and a fact text does not reach a rule", () -> {
            Map<Integer, DatalogSite> s = sites(rule("M.a", "S<-"), fact("M.a", "S(1)"));
            eq(set(1), DatalogMap.reached(s, "M.t", List.of("S<-"), List.of()));
            eq(set(2), DatalogMap.reached(s, "M.t", List.of(), List.of("S(1).")));
        });

        c.test("a fact is reached by its text; white space and the final dot do not count", () -> {
            Map<Integer, DatalogSite> s = sites(fact("M.a", "Edge(1,2)"), fact("M.a", "Edge(2,3)"));
            eq(set(1), DatalogMap.reached(s, "M.t", List.of(), List.of("Edge(1, 2).")));
            eq(set(1, 2), DatalogMap.reached(s, "M.t", List.of(), List.of("Edge(1,2)", "Edge( 2, 3 ).")));
        });

        c.test("a fact that no source fact has the text of reaches the facts of its predicate", () -> {
            Map<Integer, DatalogSite> s = sites(fact("M.a", "Edge(x,y)"), fact("M.a", "Edge(z,1)"), fact("M.a", "Node(x)"));
            eq(set(1, 2), DatalogMap.reached(s, "M.t", List.of(), List.of("Edge(5, 6).")));
        });

        c.test("constraints in the def of the test are preferred, else all with the key count", () -> {
            Map<Integer, DatalogSite> s = sites(rule("M.other", "R<-S"), rule("M.t", "R<-S"), rule("M.x", "R<-S"));
            eq(set(2), DatalogMap.reached(s, "M.t", List.of("R<-S"), List.of()));
            eq(set(1, 2, 3), DatalogMap.reached(s, "M.elsewhere", List.of("R<-S"), List.of()));
        });

        c.test("a test that solved nothing reaches nothing", () ->
            eq(set(), DatalogMap.reached(sites(rule("M.a", "R<-S"), fact("M.a", "S(1)")), "M.t", List.of(), List.of())));

        c.test("the predicate of a key", () -> {
            eq("Reach", DatalogSite.head("Reach<-Start,Edge"));
            eq("$Result", DatalogSite.head("$Result<-Even"));
            eq("Edge", DatalogSite.head("Edge(1,2)"));
        });

        c.test("the key of a fact and of a rule", () -> {
            eq("Edge(1,2)", DatalogFinder.factKey("Edge(1, 2)."));
            eq("Name(\"a b\")", DatalogFinder.factKey("Name(\"a b\")."));
            eq("Reach<-Start,Edge", DatalogFinder.ruleKey("Reach", List.of("Start", "Edge")));
            eq("Dead<-", DatalogFinder.ruleKey("Dead", List.of()));
        });

        c.finish();
    }
}
