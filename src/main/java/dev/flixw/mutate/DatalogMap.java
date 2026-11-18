package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Maps what a test's solves contained to the rows of the site table. The solver input carries no source position, so
 * the mapping is by name, as in the coverage spike (#22): a rule by its signature ({@code Head<-Body1,Body2}), a fact by its
 * text (without white space and the final dot), or, if no fact of the sources has that text (a fact written with
 * variables or an expression), by its predicate name. All sites with a matching key count: the solver mutation also
 * acts by key, even when the sites belong to different test definitions. Equal rules or facts are not told apart: all
 * of them read as reached. That can only make a site read "reached" that no test reached, never the reverse.
 */
final class DatalogMap {
    private DatalogMap() { }

    /**
     * The ids of the sites that a test with the given solves reached.
     *
     * @param sites the Datalog rows of the table by id
     * @param test  the name of the test, as the compiler names its def ({@code Module.name})
     * @param rules the rule signatures of the solves of the test
     * @param facts the fact texts of the solves of the test
     */
    static TreeSet<Integer> reached(Map<Integer, DatalogSite> sites, String test, Collection<String> rules, Collection<String> facts) {
        TreeSet<Integer> out = new TreeSet<>();
        for (String sig : rules) out.addAll(matching(sites, Point.Kind.RULE, sig, false));
        for (String text : facts) {
            String key = DatalogFinder.factKey(text);
            List<Integer> exact = matching(sites, Point.Kind.FACT, key, false);
            out.addAll(exact.isEmpty() ? matching(sites, Point.Kind.FACT, DatalogSite.head(key), true) : exact);
        }
        return out;
    }

    /**
     * Whether a site key equals a key from a solve. The solver prints a string without quotes, and the key of a source fact keeps them,
     * so for facts quotes and white space are left out on both sides.
     */
    private static boolean same(Point.Kind kind, String siteKey, String key) {
        return kind == Point.Kind.FACT ? bare(siteKey).equals(bare(key)) : siteKey.equals(key);
    }

    private static String bare(String key) { return key.replaceAll("[\"\\s]", ""); }

    /** The sites of the kind whose key (or, with {@code byHead}, whose predicate) is {@code key}. */
    private static List<Integer> matching(Map<Integer, DatalogSite> sites, Point.Kind kind, String key, boolean byHead) {
        List<Integer> all = new ArrayList<>();
        for (Map.Entry<Integer, DatalogSite> e : sites.entrySet()) {
            DatalogSite d = e.getValue();
            if (d.kind() != kind || !(byHead ? DatalogSite.head(d.key()).equals(key) : same(kind, d.key(), key))) continue;
            all.add(e.getKey());
        }
        return all;
    }
}
