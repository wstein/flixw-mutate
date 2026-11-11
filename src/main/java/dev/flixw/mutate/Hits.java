package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Which test reached which site of a {@link SiteTable}: one {@link BitSet} per test, indexed by the site id.
 * The only level that is recorded is {@link #REACHED}: the run of the test evaluated the site (a switch of
 * the marker), or the solve of the test contained the rule or fact. It does not say that a Datalog rule
 * derived a tuple, or that code was "used". {@link #FIRED} is the name of the next level and is never stored.
 */
public final class Hits {
    public static final int NOT_REACHED = 0;
    public static final int REACHED = 1;
    /** Reserved: the rule derived at least one tuple. Not recorded by this version. */
    public static final int FIRED = 2;

    private final int size;
    private final SortedMap<String, BitSet> byTest;

    private Hits(int size, SortedMap<String, BitSet> byTest) {
        this.size = size;
        this.byTest = byTest;
    }

    /** Collects the hits of a run; the tests are given up front so that a test without a hit is still a test. */
    public static final class Builder {
        private final int size;
        private final SortedMap<String, BitSet> byTest = new TreeMap<>();

        public Builder(SiteTable sites, Collection<String> tests) {
            size = sites.size();
            for (String t : tests) byTest.put(t, new BitSet(size + 1));
        }

        public void reach(String test, int id) {
            BitSet b = byTest.get(test);
            if (b == null) throw new IllegalArgumentException("unknown test " + test);
            if (id < 1 || id > size) throw new IllegalArgumentException("no site " + id + " in a table of " + size);
            b.set(id);
        }

        public Hits build() {
            SortedMap<String, BitSet> copy = new TreeMap<>();
            for (Map.Entry<String, BitSet> e : byTest.entrySet()) copy.put(e.getKey(), (BitSet) e.getValue().clone());
            return new Hits(size, java.util.Collections.unmodifiableSortedMap(copy));
        }
    }

    /** The tests, in name order. */
    public List<String> tests() { return new ArrayList<>(byTest.keySet()); }

    /** {@link #REACHED} if {@code test} reached site {@code id}, else {@link #NOT_REACHED}. */
    public int level(String test, int id) {
        if (id < 1 || id > size) throw new IllegalArgumentException("no site " + id + " in a table of " + size);
        BitSet b = byTest.get(test);
        if (b == null) throw new IllegalArgumentException("unknown test " + test);
        return b.get(id) ? REACHED : NOT_REACHED;
    }

    /** The ids that {@code test} reached (a copy). */
    public BitSet idsOf(String test) {
        BitSet b = byTest.get(test);
        if (b == null) throw new IllegalArgumentException("unknown test " + test);
        return (BitSet) b.clone();
    }

    /** The tests that reached site {@code id}, in name order. */
    public List<String> testsFor(int id) {
        if (id < 1 || id > size) throw new IllegalArgumentException("no site " + id + " in a table of " + size);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, BitSet> e : byTest.entrySet()) if (e.getValue().get(id)) out.add(e.getKey());
        return out;
    }

    /** The ids that any test reached. */
    public BitSet union() {
        BitSet u = new BitSet(size + 1);
        for (BitSet b : byTest.values()) u.or(b);
        return u;
    }

    @Override public boolean equals(Object o) {
        return o instanceof Hits h && h.size == size && h.byTest.equals(byTest);
    }

    @Override public int hashCode() { return byTest.hashCode() * 31 + size; }

    @Override public String toString() { return "Hits" + byTest; }
}
