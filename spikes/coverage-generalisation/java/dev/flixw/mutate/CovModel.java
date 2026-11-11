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
        TreeMap<Integer, Character> out = new TreeMap<>();
        for (var en : owners(file, true).entrySet()) {
            int[] c = new int[2]; // hit owners, missed owners
            for (var o : en.getValue().entrySet()) c[hit.get(o.getKey()) ? 0 : 1] += o.getValue();
            out.put(en.getKey(), c[1] == 0 ? 'H' : c[0] == 0 ? 'M' : 'P');
        }
        return out;
    }

    /** Line to (owning region id to number of expression starts of the line owned by it). */
    TreeMap<Integer, TreeMap<Integer, Integer>> owners(String file, boolean countUnowned) {
        List<CovSites.Region> rs = new ArrayList<>();
        for (CovSites.Region r : sites.regions) if (r.file().equals(file)) rs.add(r);
        rs.sort(Comparator.<CovSites.Region>comparingInt(CovSites.Region::start).thenComparing(r -> -r.end()).thenComparingInt(CovSites.Region::id));
        List<CovSites.Exec> es = new ArrayList<>();
        for (CovSites.Exec e : sites.execs) if (e.file().equals(file)) es.add(e);
        es.sort(Comparator.comparingInt(CovSites.Exec::offset));
        TreeMap<Integer, TreeMap<Integer, Integer>> count = new TreeMap<>();
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
            if (owner == null) { if (countUnowned) unowned++; continue; }
            count.computeIfAbsent(e.line(), k -> new TreeMap<>()).merge(owner.id(), 1, Integer::sum);
        }
        return count;
    }

    /** The model as a site table and hits (regions and the parts of lines they own), ids by file and offset. */
    record Tabled(SiteTable sites, Hits hits) {}

    Tabled tabled(Map<String, String> rel) {
        record Key(Point.Kind kind, String file, int line, int start, int end) {}
        java.util.function.Function<String, String> name = f -> java.nio.file.Path.of(rel.get(f)).getFileName().toString();
        List<Point> drafts = new ArrayList<>();
        Map<Integer, Key> regionKey = new TreeMap<>();
        Map<Integer, List<Key>> lineKeys = new TreeMap<>(); // region id to the line parts it owns
        for (CovSites.Region r : sites.regions) {
            Point.Kind k = switch (r.kind()) { case ENTRY -> Point.Kind.FUNCTION; case THEN -> Point.Kind.THEN; case ELSE -> Point.Kind.ELSE; case ARM -> Point.Kind.ARM; case LAMBDA -> Point.Kind.LAMBDA; };
            Key key = new Key(k, name.apply(r.file()), r.line(), r.start(), r.end());
            regionKey.put(r.id(), key);
            drafts.add(new Point(0, k, key.file(), key.line(), new Span(r.start(), r.end())));
        }
        java.util.Set<Key> seen = new java.util.HashSet<>();
        for (String f : files()) {
            for (var en : owners(f, false).entrySet())
                for (int owner : en.getValue().keySet()) {
                    CovSites.Region r = sites.regions.stream().filter(x -> x.id() == owner).findFirst().orElseThrow();
                    Key key = new Key(Point.Kind.LINE, name.apply(f), en.getKey(), r.start(), r.end());
                    if (seen.add(key)) {
                        drafts.add(new Point(0, Point.Kind.LINE, key.file(), key.line(), new Span(r.start(), r.end())));
                        lineKeys.computeIfAbsent(owner, k -> new ArrayList<>()).add(key);
                    }
                }
        }
        SiteTable t = SiteTable.sorted(drafts);
        Map<Key, Integer> id = new java.util.HashMap<>();
        for (Point p : t.points())
            if (id.put(new Key(p.kind(), p.file(), p.line(), p.span().start(), p.span().end()), p.id()) != null)
                throw new IllegalStateException("two sites with the same key: " + p);
        Hits.Builder b = new Hits.Builder(t, perTest.keySet());
        for (var e : perTest.entrySet())
            for (int r = e.getValue().nextSetBit(0); r >= 0; r = e.getValue().nextSetBit(r + 1)) {
                b.reach(e.getKey(), id.get(regionKey.get(r)));
                for (Key lk : lineKeys.getOrDefault(r, List.of())) b.reach(e.getKey(), id.get(lk));
            }
        return new Tabled(t, b.build());
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
