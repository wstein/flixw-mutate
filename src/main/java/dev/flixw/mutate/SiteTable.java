package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sites of a run in one dense id space: ids are 1 to {@code size()} (0 is the marker's "no mutant"), in the
 * order of file, then offset, then the wider span first (the order of {@link Schemata#render}), so that the ids
 * are the same in every run of the same sources. Immutable.
 */
public record SiteTable(List<Point> points) {
    /**
     * The order of files for switch numbers and ids: the path relative to the project, with '/' as separator,
     * compared as text. {@code Path} order differs (by bytes on Linux, case-blind with '\\' on Windows).
     */
    static final Comparator<String> FILE_ORDER = Comparator.naturalOrder();

    static final Comparator<Point> ORDER = Comparator.<Point, String>comparing(Point::file, FILE_ORDER)
        .thenComparingInt(p -> p.span().start())
        .thenComparingInt(p -> -p.span().end());

    public SiteTable {
        points = List.copyOf(points);
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            if (p.id() != i + 1) throw new IllegalArgumentException("id " + p.id() + " at position " + (i + 1) + ": ids must be dense from 1");
            if (i > 0 && ORDER.compare(points.get(i - 1), p) > 0)
                throw new IllegalArgumentException("id " + p.id() + " is out of order by file and offset");
        }
    }

    /** A table of points that are numbered and ordered already (the engine's switch numbers). */
    public static SiteTable of(List<Point> numbered) { return new SiteTable(numbered); }

    /** Sorts the drafts by file and offset (ties keep their order) and numbers them from 1; their own ids are ignored. */
    public static SiteTable sorted(List<Point> drafts) {
        List<Point> sorted = new ArrayList<>(drafts);
        sorted.sort(ORDER);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) out.add(sorted.get(i).withId(i + 1));
        return new SiteTable(out);
    }

    public int size() { return points.size(); }

    /** The point with id {@code id} (1 to size). */
    public Point get(int id) {
        if (id < 1 || id > points.size()) throw new IllegalArgumentException("no site " + id + " in a table of " + points.size());
        return points.get(id - 1);
    }
}
