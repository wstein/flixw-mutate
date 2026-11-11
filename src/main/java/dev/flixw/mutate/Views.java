package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports as functions of a {@link SiteTable} and a set of reached ids. Rows have the form of the ground-truth
 * files of the coverage spike (tab separated, no header).
 */
public final class Views {
    private Views() { }

    private static String flag(BitSet hit, int id) { return hit.get(id) ? "1" : "0"; }

    /** {@code file line hit} for each FUNCTION site. */
    public static List<String> functions(SiteTable sites, BitSet hit) {
        List<String> out = new ArrayList<>();
        for (Point p : sites.points())
            if (p.kind() == Point.Kind.FUNCTION) out.add(p.file() + "\t" + p.line() + "\t" + flag(hit, p.id()));
        return out;
    }

    /**
     * {@code file line kind index hit} for each THEN, ELSE and ARM site; kind is {@code if} or {@code match}, the
     * index counts the sites of the same kind on the same line in id order (two {@code if} on one line would share
     * the count: a limit of the model).
     */
    public static List<String> branches(SiteTable sites, BitSet hit) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> next = new HashMap<>();
        for (Point p : sites.points()) {
            String kind = switch (p.kind()) {
                case THEN, ELSE -> "if";
                case ARM -> "match";
                default -> null;
            };
            if (kind == null) continue;
            int index = next.merge(p.file() + "\t" + p.line() + "\t" + kind, 1, Integer::sum) - 1;
            out.add(p.file() + "\t" + p.line() + "\t" + kind + "\t" + index + "\t" + flag(hit, p.id()));
        }
        return out;
    }

    /**
     * {@code file:line:S} for each line with LINE sites: S is H if all its sites were reached, M if none, P if some.
     */
    public static List<String> lines(SiteTable sites, BitSet hit) {
        Map<String, int[]> count = new java.util.LinkedHashMap<>(); // reached, not reached
        for (Point p : sites.points())
            if (p.kind() == Point.Kind.LINE) count.computeIfAbsent(p.file() + ":" + p.line(), k -> new int[2])[hit.get(p.id()) ? 0 : 1]++;
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : count.entrySet()) {
            int[] c = e.getValue();
            out.add(e.getKey() + ":" + (c[1] == 0 ? "H" : c[0] == 0 ? "M" : "P"));
        }
        return out;
    }

    /** {@code test line} for each test and each site of {@code kind} that the test reached, by test name and id. */
    public static List<String> reached(SiteTable sites, Hits hits, Point.Kind kind) {
        List<String> out = new ArrayList<>();
        for (String t : hits.tests())
            for (Point p : sites.points())
                if (p.kind() == kind && hits.level(t, p.id()) == Hits.REACHED) out.add(t + "\t" + p.line());
        return out;
    }
}
