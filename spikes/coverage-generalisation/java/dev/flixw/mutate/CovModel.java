package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The result of a baseline run: which tests hit which regions, and the line, branch and function views derived
 * from it. A line is H (all expressions starting on it belong to hit regions), M (none) or P (some).
 */
final class CovModel {
    final CovSites sites;
    /** Test name to the ids of the regions it hit, in test order. */
    final TreeMap<String, BitSet> perTest = new TreeMap<>();
    int unowned;
    static final boolean OUTER = "outer".equals(System.getProperty("cov.sabotage"));

    CovModel(CovSites sites) { this.sites = sites; }

    BitSet all() {
        BitSet b = new BitSet();
        for (BitSet t : perTest.values()) b.or(t);
        return b;
    }

    /** Line statuses of one file for the hit set {@code hit}: line to 'H', 'M' or 'P'. */
    TreeMap<Integer, Character> lines(String file, BitSet hit) {
        List<CovSites.Region> rs = new ArrayList<>();
        for (CovSites.Region r : sites.regions) if (r.file().equals(file)) rs.add(r);
        rs.sort(Comparator.<CovSites.Region>comparingInt(CovSites.Region::start).thenComparing(r -> -r.end()).thenComparingInt(CovSites.Region::id));
        List<CovSites.Exec> es = new ArrayList<>();
        for (CovSites.Exec e : sites.execs) if (e.file().equals(file)) es.add(e);
        es.sort(Comparator.comparingInt(CovSites.Exec::offset));
        TreeMap<Integer, int[]> count = new TreeMap<>(); // line to {hit owners, missed owners}
        List<CovSites.Region> open = new ArrayList<>();
        int next = 0;
        for (CovSites.Exec e : es) {
            while (next < rs.size() && rs.get(next).start() <= e.offset()) open.add(rs.get(next++));
            while (!open.isEmpty() && open.get(open.size() - 1).end() <= e.offset()) open.remove(open.size() - 1);
            // innermost region containing the offset: the last open one whose end is after the offset
            CovSites.Region owner = null;
            for (int i = open.size() - 1; i >= 0; i--) {
                if (open.get(i).end() > e.offset()) { owner = open.get(i); break; }
            }
            if (OUTER && !open.isEmpty()) owner = open.get(0);
            if (owner == null) { unowned++; continue; }
            int[] c = count.computeIfAbsent(e.line(), k -> new int[2]);
            c[hit.get(owner.id()) ? 0 : 1]++;
        }
        TreeMap<Integer, Character> out = new TreeMap<>();
        for (var en : count.entrySet()) {
            int[] c = en.getValue();
            out.put(en.getKey(), c[1] == 0 ? 'H' : c[0] == 0 ? 'M' : 'P');
        }
        return out;
    }

    List<CovSites.Region> of(String file, CovSites.Kind... kinds) {
        List<CovSites.Region> out = new ArrayList<>();
        for (CovSites.Region r : sites.regions) {
            if (!r.file().equals(file)) continue;
            for (CovSites.Kind k : kinds) if (r.kind() == k) { out.add(r); break; }
        }
        return out;
    }

    List<String> files() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>();
        for (CovSites.Region r : sites.regions) s.add(r.file());
        return new ArrayList<>(s);
    }

    /** Tests per region id, as a histogram: number of tests to number of regions. */
    Map<Integer, Integer> testsPerRegion() {
        Map<Integer, Integer> h = new TreeMap<>();
        for (CovSites.Region r : sites.regions) {
            int n = 0;
            for (BitSet t : perTest.values()) if (t.get(r.id())) n++;
            h.merge(n, 1, Integer::sum);
        }
        return h;
    }
}
