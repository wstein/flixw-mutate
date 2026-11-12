package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The numbering of the sites of a run: the mutation sites (one {@link Point.Kind#MUTANT} row per mutant), the
 * regions (one row each, a marker in the program) and the parts of lines that the regions own (one
 * {@link Point.Kind#LINE} row each, no marker: it is reached when its region is) in one dense id space, ordered by
 * file, offset and the wider span first. At equal spans a region comes before a site (it wraps it), and a line part
 * after both. The order is total, so the ids are the same in every run on the same sources.
 *
 * <p>The Datalog rules and facts of the sources ({@link DatalogSite}) are rows as well ({@link Point.Kind#RULE},
 * {@link Point.Kind#FACT}); they have no marker and no rewrite, a solve of a test reaches them (see {@link DatalogMap}).
 * At equal spans they come last.
 *
 * <p>A region that overlaps a site other than by containing it or lying in one of its operands, or another region
 * other than by nesting, is left out and counted: the program must stay what it was, and the mutants must stay
 * the mutants of the run without regions.
 */
final class SitePlan {
    /** One file of the program, in the order of {@link SiteTable#FILE_ORDER}; {@code file} is relative to the project. */
    record Input(String file, String text, List<Site> sites, List<Region> regions, List<Exec> execs, List<DatalogSite> datalog) {
        Input(String file, String text, List<Site> sites, List<Region> regions, List<Exec> execs) {
            this(file, text, sites, regions, execs, List.of());
        }
    }

    private final SiteTable table;
    private final Map<String, List<Schemata.Item>> items;
    private final Map<Integer, List<Integer>> lineParts;
    private final Map<Integer, DatalogSite> datalog;
    private final int skippedRegions;

    private SitePlan(SiteTable table, Map<String, List<Schemata.Item>> items, Map<Integer, List<Integer>> lineParts,
                     Map<Integer, DatalogSite> datalog, int skippedRegions) {
        this.table = table;
        this.items = items;
        this.lineParts = lineParts;
        this.datalog = datalog;
        this.skippedRegions = skippedRegions;
    }

    /** The Datalog rules and facts by their ids in the table, in id order. */
    Map<Integer, DatalogSite> datalogSites() { return datalog; }

    /** The table: mutants, regions and line parts. */
    SiteTable table() { return table; }

    /** What to rewrite in a file ({@code Input.file}), in id order. */
    List<Schemata.Item> items(String file) { return items.getOrDefault(file, List.of()); }

    /** The ids of the line parts that region {@code id} owns (empty for a site that is not a region). */
    List<Integer> lineParts(int id) { return lineParts.getOrDefault(id, List.of()); }

    /** The regions left out because they overlap a site or another region badly. */
    int skippedRegions() { return skippedRegions; }

    /** A thing to place: a region, a site or a line part, with the keys of its place in the order. */
    private static final class Entry {
        final int start;
        final int end;
        final int group;
        final int sub;
        final int sub2;
        Region region;
        Site site;
        int line;
        Region owner;
        DatalogSite datalog;

        Entry(int start, int end, int group, int sub, int sub2) {
            this.start = start;
            this.end = end;
            this.group = group;
            this.sub = sub;
            this.sub2 = sub2;
        }
    }

    private static final int REGION = 0;
    private static final int SITE = 1;
    private static final int LINE = 2;
    private static final int DATALOG = 3;

    private static final Comparator<Entry> ORDER = Comparator.<Entry>comparingInt(e -> e.start)
        .thenComparingInt(e -> -e.end).thenComparingInt(e -> e.group).thenComparingInt(e -> e.sub).thenComparingInt(e -> e.sub2);

    static SitePlan build(List<Input> inputs) {
        List<Point> points = new ArrayList<>();
        Map<String, List<Schemata.Item>> items = new HashMap<>();
        Map<Integer, List<Integer>> lineParts = new HashMap<>();
        Map<Integer, DatalogSite> datalog = new java.util.TreeMap<>();
        int skipped = 0;
        for (Input in : inputs) {
            List<Region> regions = distinct(in.regions());
            regions = withoutSiteConflicts(regions, in.sites());
            regions = nested(regions);
            skipped += in.regions().size() - regions.size();
            int[] lineStarts = lineStarts(in.text());
            List<Entry> entries = new ArrayList<>();
            for (Region r : regions) {
                Entry e = new Entry(r.span().start(), r.span().end(), REGION, r.kind().ordinal(), 0);
                e.region = r;
                e.line = r.line();
                entries.add(e);
            }
            for (Site s : in.sites()) {
                Entry e = new Entry(s.whole().start(), s.whole().end(), SITE, 0, 0);
                e.site = s;
                e.line = lineOf(lineStarts, s.whole().start());
                entries.add(e);
            }
            for (var owned : lineOwners(regions, in.execs())) {
                Entry e = new Entry(owned.getKey().span().start(), owned.getKey().span().end(), LINE, owned.getValue(), owned.getKey().kind().ordinal());
                e.owner = owned.getKey();
                e.line = owned.getValue();
                entries.add(e);
            }
            for (DatalogSite d : in.datalog()) {
                Entry e = new Entry(d.span().start(), d.span().end(), DATALOG, d.kind().ordinal(), 0);
                e.datalog = d;
                e.line = d.line();
                entries.add(e);
            }
            entries.sort(ORDER);
            List<Schemata.Item> fileItems = new ArrayList<>();
            Map<Region, Integer> regionId = new HashMap<>();
            for (Entry e : entries) {
                int id = points.size() + 1;
                switch (e.group) {
                    case REGION -> {
                        points.add(new Point(id, e.region.kind(), in.file(), e.line, e.region.span()));
                        fileItems.add(Schemata.Item.ofRegion(e.region.span(), id));
                        regionId.put(e.region, id);
                    }
                    case SITE -> {
                        fileItems.add(Schemata.Item.ofSite(e.site, id));
                        for (int i = 0; i < e.site.kind().mutations().size(); i++)
                            points.add(new Point(id + i, Point.Kind.MUTANT, in.file(), e.line, e.site.whole()));
                    }
                    case DATALOG -> {
                        points.add(new Point(id, e.datalog.kind(), in.file(), e.line, e.datalog.span()));
                        datalog.put(id, e.datalog);
                    }
                    default -> {
                        points.add(new Point(id, Point.Kind.LINE, in.file(), e.line, e.owner.span()));
                        lineParts.computeIfAbsent(regionId.get(e.owner), k -> new ArrayList<>()).add(id);
                    }
                }
            }
            items.put(in.file(), fileItems);
        }
        return new SitePlan(SiteTable.of(points), items, lineParts, datalog, skipped);
    }

    /** The regions without a second one of the same kind and span. */
    private static List<Region> distinct(List<Region> regions) {
        Set<String> seen = new HashSet<>();
        List<Region> out = new ArrayList<>();
        for (Region r : regions)
            if (seen.add(r.kind() + ":" + r.span().start() + ":" + r.span().end())) out.add(r);
        return out;
    }

    /** The regions that contain every site they touch or lie in an operand of it, or touch none. */
    private static List<Region> withoutSiteConflicts(List<Region> regions, List<Site> sites) {
        List<Region> out = new ArrayList<>();
        for (Region r : regions) {
            Span w = r.span();
            boolean ok = true;
            for (Site s : sites) {
                Span sw = s.whole();
                if (w.end() <= sw.start() || sw.end() <= w.start()) continue;
                if (w.contains(sw)) continue;
                if (sw.contains(w) && (s.left().contains(w) || (s.right() != null && s.right().contains(w)))) continue;
                ok = false;
                break;
            }
            if (ok) out.add(r);
        }
        return out;
    }

    /** The regions that nest: one that overlaps an earlier one (by start, then wider first) without lying in it is left out. */
    private static List<Region> nested(List<Region> regions) {
        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.<Region>comparingInt(r -> r.span().start()).thenComparingInt(r -> -r.span().end()).thenComparingInt(r -> r.kind().ordinal()));
        List<Region> out = new ArrayList<>();
        List<Region> open = new ArrayList<>();
        for (Region r : sorted) {
            while (!open.isEmpty() && open.get(open.size() - 1).span().end() <= r.span().start()) open.remove(open.size() - 1);
            if (!open.isEmpty() && !open.get(open.size() - 1).span().contains(r.span())) continue;
            out.add(r);
            open.add(r);
        }
        return out;
    }

    /**
     * The pairs (innermost region, line) of the expression starts: a line belongs, in parts, to the regions that
     * own an expression start on it. Each pair once, in a fixed order.
     */
    private static Set<Map.Entry<Region, Integer>> lineOwners(List<Region> regions, List<Exec> execs) {
        List<Region> rs = new ArrayList<>(regions);
        rs.sort(Comparator.<Region>comparingInt(r -> r.span().start()).thenComparingInt(r -> -r.span().end()).thenComparingInt(r -> r.kind().ordinal()));
        List<Exec> es = new ArrayList<>(execs);
        es.sort(Comparator.comparingInt(Exec::offset));
        Set<Map.Entry<Region, Integer>> out = new LinkedHashSet<>();
        List<Region> open = new ArrayList<>();
        int next = 0;
        for (Exec e : es) {
            while (next < rs.size() && rs.get(next).span().start() <= e.offset()) open.add(rs.get(next++));
            while (!open.isEmpty() && open.get(open.size() - 1).span().end() <= e.offset()) open.remove(open.size() - 1);
            if (open.isEmpty()) continue;
            out.add(Map.entry(open.get(open.size() - 1), e.line()));
        }
        return out;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The 1-based line of an offset. */
    private static int lineOf(int[] starts, int offset) {
        int i = java.util.Arrays.binarySearch(starts, offset);
        return (i >= 0 ? i : -i - 2) + 1;
    }
}
