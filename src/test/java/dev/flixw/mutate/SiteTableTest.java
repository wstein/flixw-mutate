package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The site table and the hit table, on the ground-truth files of the coverage spike: the truth is read from the
 * TSV files (and the line annotations) into a table, and the views of the table must give the truth back.
 * The truth files carry no offsets: the spans below are made up from line and slot, so that the order of the
 * ids is the order of (file, offset) as in a real run (the real offsets are compared by the spike harness).
 */
public final class SiteTableTest {
    static final Path SPIKE = Path.of(System.getenv("FLIXW_TEST_COVERAGE_SPIKE")).toAbsolutePath();
    static final Path FIXTURE = SPIKE.resolve("fixture");
    static final Path DATALOG = SPIKE.resolve("datalog-fixture");
    static final String ALL = "(all tests)";

    static List<String[]> tsv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String[]> out = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) out.add(l.split("\t", -1));
        return out;
    }

    static Point draft(Point.Kind kind, String file, int line, int slot) {
        return new Point(0, kind, file, line, new Span(line * 1000 + slot, line * 1000 + slot + 1));
    }

    /** The id the table gave to the draft of (file, line, kind, slot). */
    static int idOf(SiteTable t, Point.Kind kind, String file, int line, int slot) {
        for (Point p : t.points())
            if (p.kind() == kind && p.file().equals(file) && p.line() == line && p.span().start() == line * 1000 + slot) return p.id();
        throw new AssertionError("no point " + kind + " " + file + ":" + line + "/" + slot);
    }

    /** A truth row to add: the draft, and whether the truth says it was hit. */
    record Row(Point point, boolean hit) { }

    static SiteTable table(List<Row> rows) {
        List<Point> drafts = new ArrayList<>();
        for (Row r : rows) drafts.add(r.point());
        Collections.shuffle(drafts, new java.util.Random(7)); // the table sorts; the input order must not matter
        return SiteTable.sorted(drafts);
    }

    static List<String> sorted(List<String> rows) {
        List<String> copy = new ArrayList<>(rows);
        Collections.sort(copy);
        return copy;
    }

    static String join(String... c) { return String.join("\t", c); }

    /** The functional truth of the spike fixture as rows of a table: functions, branches, lines. */
    static List<Row> functionalTruth() throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String[] c : tsv(FIXTURE.resolve("expected-functions.tsv")))
            rows.add(new Row(draft(Point.Kind.FUNCTION, c[0], Integer.parseInt(c[1]), 0), c[2].equals("1")));
        for (String[] c : tsv(FIXTURE.resolve("expected-branches.tsv"))) {
            int index = Integer.parseInt(c[3]);
            Point.Kind kind = c[2].equals("match") ? Point.Kind.ARM : index == 0 ? Point.Kind.THEN : Point.Kind.ELSE;
            rows.add(new Row(draft(kind, c[0], Integer.parseInt(c[1]), 1 + index), c[4].equals("1")));
        }
        try (var files = Files.list(FIXTURE.resolve("src"))) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.equals("Limits.flix")) continue; // its partial lines are the known limits, compared on their own
                String[] text = Files.readString(f, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < text.length; i++) {
                    String l = text[i].stripTrailing();
                    int k = l.lastIndexOf("// ");
                    if (k < 0 || l.length() != k + 4 || "HMP".indexOf(l.charAt(k + 3)) < 0) continue;
                    char s = l.charAt(k + 3);
                    // H: one point, hit; M: one point, not hit; P: two points on the line, one hit and one not
                    if (s != 'M') rows.add(new Row(draft(Point.Kind.LINE, name, i + 1, 10), true));
                    if (s != 'H') rows.add(new Row(draft(Point.Kind.LINE, name, i + 1, s == 'P' ? 11 : 10), false));
                }
            }
        }
        return rows;
    }

    /** The truth lines as the annotations give them: file:line:H|M|P. */
    static List<String> annotatedLines() throws IOException {
        List<String> out = new ArrayList<>();
        try (var files = Files.list(FIXTURE.resolve("src"))) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.equals("Limits.flix")) continue;
                String[] text = Files.readString(f, StandardCharsets.UTF_8).split("\n", -1);
                for (int i = 0; i < text.length; i++) {
                    String l = text[i].stripTrailing();
                    int k = l.lastIndexOf("// ");
                    if (k >= 0 && l.length() == k + 4 && "HMP".indexOf(l.charAt(k + 3)) >= 0) out.add(name + ":" + (i + 1) + ":" + l.charAt(k + 3));
                }
            }
        }
        return out;
    }

    static Hits hitsOf(SiteTable t, List<Row> rows, String test) {
        Hits.Builder b = new Hits.Builder(t, List.of(test));
        for (Row r : rows) {
            Point p = r.point();
            if (r.hit()) b.reach(test, idOf(t, p.kind(), p.file(), p.line(), p.span().start() - p.line() * 1000));
        }
        return b.build();
    }

    // --- Datalog: rules and facts per test --------------------------------------------------------------------

    static final String DL = "DlNet.flix";
    /** The constraint set that each test's solve contains, with the query rule of the def: source lines. */
    static final Map<String, int[]> SETS = Map.of("TestNet.testReachable", new int[] {5, 13}, "TestNet.testEvens", new int[] {19, 23});

    /** The fact sites of DlNet.flix: every {@code Name(args).} on a line that is not a rule, with its offset. */
    static List<Point> factSites() throws IOException {
        String[] text = Files.readString(DATALOG.resolve("src").resolve(DL), StandardCharsets.UTF_8).split("\n", -1);
        List<Point> out = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < text.length; i++) {
            if (!text[i].contains(":-")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Z]\\w*\\([^()]*\\)\\.").matcher(text[i]);
                while (m.find()) out.add(new Point(0, Point.Kind.FACT, DL, i + 1, new Span(offset + m.start(), offset + m.end())));
            }
            offset += text[i].length() + 1;
        }
        return out;
    }

    static SiteTable datalogTable() throws IOException {
        List<Point> drafts = new ArrayList<>(factSites());
        String[] text = Files.readString(DATALOG.resolve("src").resolve(DL), StandardCharsets.UTF_8).split("\n", -1);
        int[] starts = new int[text.length + 1];
        for (int i = 0; i < text.length; i++) starts[i + 1] = starts[i] + text[i].length() + 1;
        for (String[] c : tsv(DATALOG.resolve("expected-rules.tsv"))) {
            int line = Integer.parseInt(c[3]);
            Point p = new Point(0, Point.Kind.RULE, DL, line, new Span(starts[line - 1] + text[line - 1].indexOf(text[line - 1].strip()), starts[line] - 1));
            if (!drafts.contains(p)) drafts.add(p);
        }
        return SiteTable.sorted(drafts);
    }

    /** The hits of a solve: the test reaches every rule and fact of the constraint set it solves, derived or not. */
    static Hits datalogHits(SiteTable t) throws IOException {
        List<String> tests = new ArrayList<>(SETS.keySet());
        tests.add("TestNet.testDouble");
        Hits.Builder b = new Hits.Builder(t, tests);
        for (Map.Entry<String, int[]> e : SETS.entrySet())
            for (Point p : t.points())
                if (p.line() >= e.getValue()[0] && p.line() <= e.getValue()[1]) b.reach(e.getKey(), p.id());
        return b.build();
    }

    public static void main(String[] args) {
        Check c = new Check("SiteTableTest");

        c.test("ids are dense from 1 and ordered by file, offset, then wider span first", () -> {
            SiteTable t = SiteTable.sorted(List.of(
                new Point(0, Point.Kind.MUTANT, "b.flix", 1, new Span(5, 6)),
                new Point(0, Point.Kind.MUTANT, "a.flix", 3, new Span(40, 41)),
                new Point(0, Point.Kind.MUTANT, "a.flix", 1, new Span(10, 12)),
                new Point(0, Point.Kind.MUTANT, "a.flix", 1, new Span(10, 20))));
            eq(4, t.size());
            eq(List.of(1, 2, 3, 4), t.points().stream().map(Point::id).toList());
            eq(List.of("a.flix:10-20", "a.flix:10-12", "a.flix:40-41", "b.flix:5-6"),
                t.points().stream().map(p -> p.file() + ":" + p.span().start() + "-" + p.span().end()).toList());
            eq(t.get(3), t.points().get(2));
        });

        c.test("a table built from points needs ids 1..n in the order of the list", () -> {
            Point a = new Point(1, Point.Kind.MUTANT, "a", 1, new Span(0, 1));
            Point b = new Point(2, Point.Kind.MUTANT, "a", 1, new Span(1, 2));
            eq(2, SiteTable.of(List.of(a, b)).size());
            throwsA(IllegalArgumentException.class, () -> SiteTable.of(List.of(b, a)));
            throwsA(IllegalArgumentException.class, () -> SiteTable.of(List.of(new Point(2, Point.Kind.MUTANT, "a", 1, new Span(0, 1)))));
            throwsA(IllegalArgumentException.class, () -> SiteTable.of(List.of(new Point(0, Point.Kind.MUTANT, "a", 1, new Span(0, 1)))));
            // out of order in (file, offset): not a table
            throwsA(IllegalArgumentException.class, () -> SiteTable.of(List.of(
                new Point(1, Point.Kind.MUTANT, "a", 1, new Span(9, 10)), new Point(2, Point.Kind.MUTANT, "a", 1, new Span(1, 2)))));
        });

        c.test("a hit is the level reached of one test at one id; nothing else is recorded; fired is only a constant", () -> {
            SiteTable t = SiteTable.sorted(List.of(draft(Point.Kind.RULE, "r", 1, 0), draft(Point.Kind.RULE, "r", 2, 0)));
            Hits.Builder b = new Hits.Builder(t, List.of("t1", "t2"));
            b.reach("t1", 2);
            Hits h = b.build();
            eq(Hits.REACHED, h.level("t1", 2));
            eq(Hits.NOT_REACHED, h.level("t1", 1));
            eq(Hits.NOT_REACHED, h.level("t2", 2));
            eq(List.of("t1"), h.testsFor(2));
            eq(List.of(), h.testsFor(1));
            eq(List.of("t1", "t2"), h.tests());
            yes(Hits.FIRED > Hits.REACHED, "fired is the higher level");
            throwsA(IllegalArgumentException.class, () -> b.reach("t1", 0));
            throwsA(IllegalArgumentException.class, () -> b.reach("t1", 3));
            throwsA(IllegalArgumentException.class, () -> b.reach("nope", 1));
            throwsA(IllegalArgumentException.class, () -> h.level("t1", 3));
        });

        c.test("round trip, functions: the truth rows of expected-functions.tsv come back from the table", () -> {
            List<Row> rows = functionalTruth();
            SiteTable t = table(rows);
            Hits h = hitsOf(t, rows, ALL);
            List<String> want = new ArrayList<>();
            for (String[] r : tsv(FIXTURE.resolve("expected-functions.tsv"))) want.add(join(r));
            eq(10, want.size());
            eq(sorted(want), sorted(Views.functions(t, h.union())));
        });

        c.test("round trip, branches: the truth rows of expected-branches.tsv come back (the index is the order of the ids)", () -> {
            List<Row> rows = functionalTruth();
            SiteTable t = table(rows);
            Hits h = hitsOf(t, rows, ALL);
            List<String> want = new ArrayList<>();
            for (String[] r : tsv(FIXTURE.resolve("expected-branches.tsv"))) want.add(join(r));
            eq(18, want.size());
            eq(sorted(want), sorted(Views.branches(t, h.union())));
        });

        c.test("round trip, lines: the 26 annotated lines (H, M, P) come back", () -> {
            List<Row> rows = functionalTruth();
            SiteTable t = table(rows);
            Hits h = hitsOf(t, rows, ALL);
            List<String> want = annotatedLines();
            eq(26, want.size());
            eq(sorted(want), sorted(Views.lines(t, h.union())));
        });

        c.test("round trip, Datalog rules: each test reaches the rules of its constraint set, a rule that derived nothing too", () -> {
            SiteTable t = datalogTable();
            Hits h = datalogHits(t);
            List<String> want = new ArrayList<>();
            for (String[] r : tsv(DATALOG.resolve("expected-rules.tsv"))) want.add(join(r[0], r[3]));
            eq(6, want.size());
            eq(sorted(want), sorted(Views.reached(t, h, Point.Kind.RULE)));
            // the rule of line 11 derived 0 tuples in the truth: reached, not fired
            yes(want.contains(join("TestNet.testReachable", "11")), "the rule that derived nothing is in the truth");
            yes(Views.reached(t, h, Point.Kind.RULE).contains(join("TestNet.testReachable", "11")), "and reached");
        });

        c.test("round trip, Datalog facts and solves: expected-facts.tsv counts, and a test without a solve has no rule or fact", () -> {
            SiteTable t = datalogTable();
            Hits h = datalogHits(t);
            Map<String, Integer> got = new TreeMap<>();
            for (String row : Views.reached(t, h, Point.Kind.FACT)) got.merge(row.split("\t")[0], 1, Integer::sum);
            Map<String, Integer> want = new TreeMap<>();
            for (String[] r : tsv(DATALOG.resolve("expected-facts.tsv"))) want.put(r[0], Integer.parseInt(r[2]));
            eq(Map.of("TestNet.testReachable", 5, "TestNet.testEvens", 3), want);
            eq(want, got);
            // expected-relations.tsv: solves 1, 1, 0 (the relation sizes are not in the table)
            for (String[] r : tsv(DATALOG.resolve("expected-relations.tsv")))
                eq(Integer.parseInt(r[1]) > 0, !h.idsOf(r[0]).isEmpty());
            eq(1L, h.tests().stream().filter(x -> h.idsOf(x).isEmpty()).count());
        });

        c.test("the union of the tests of a table is the set the views read, per test the sets stay apart", () -> {
            SiteTable t = datalogTable();
            Hits h = datalogHits(t);
            BitSet a = h.idsOf("TestNet.testReachable");
            BitSet b = h.idsOf("TestNet.testEvens");
            yes(!a.intersects(b), "the two constraint sets share no site");
            BitSet u = (BitSet) a.clone();
            u.or(b);
            eq(u, h.union());
            eq(a.cardinality() + b.cardinality(), h.union().cardinality());
        });

        c.test("one file order for the switch numbers and the table: the relative path as text, '/' as separator", () -> {
            // Path order differs from this on Windows (a.flix before B.flix; FooTest.flix before Foo/Bar.flix): the comparator is called directly
            yes(SiteTable.FILE_ORDER.compare("B.flix", "a.flix") < 0, "upper case before lower case");
            yes(SiteTable.FILE_ORDER.compare("src/Foo/Bar.flix", "src/FooTest.flix") < 0, "'/' sorts before 'T'");
            // On Linux the byte order of Path differs from the text order for U+FF5A and U+1F600 (UTF-8 EF.. before F0.., UTF-16 FF5A after D83D)
            Path dir = Check.tempDir("site-table-order");
            Path src = Files.createDirectories(dir.resolve("src"));
            String bmp = "\uFF5A.flix";
            String astral = new String(Character.toChars(0x1F600)) + ".flix";
            try {
                Files.writeString(src.resolve(bmp), "");
                Files.writeString(src.resolve(astral), "");
            } catch (java.nio.file.InvalidPathException | java.nio.file.FileSystemException e) {
                return; // a file system that cannot hold the names: the direct calls above are the test
            }
            List<String> names = Session.flixFiles(src).stream().map(p -> p.getFileName().toString()).toList();
            eq(List.of(astral, bmp), names);
            List<Point> points = new ArrayList<>();
            for (String n : names) points.add(new Point(points.size() + 1, Point.Kind.MUTANT, "src/" + n, 1, new Span(0, 1)));
            eq(2, SiteTable.of(points).size());
        });

        c.test("a mutant point carries the file, line and span of its site", () -> {
            Path calc = SiteFinderTest.FIXTURE;
            Report r = Engine.run(EngineTest.config(calc, null));
            SiteTable t = r.coverage().sites();
            eq(r.results().size(), t.size());
            for (Result x : r.results()) {
                Point p = t.get(x.mutant().id());
                eq(Point.Kind.MUTANT, p.kind());
                eq(x.file(), p.file());
                eq(Integer.parseInt(x.position().substring(0, x.position().indexOf(':'))), p.line());
                eq(x.mutant().site().whole(), p.span());
            }
        });

        c.test("a point and a hit table reject what they cannot hold", () -> {
            Span s = new Span(0, 1);
            throwsA(IllegalArgumentException.class, () -> new Point(-1, Point.Kind.MUTANT, "f", 1, s));
            throwsA(IllegalArgumentException.class, () -> new Point(1, Point.Kind.MUTANT, "f", 0, s));
            new Point(0, Point.Kind.MUTANT, "f", 1, s); // a draft id 0 and line 1 are the limits that are valid
        });

        c.test("a built hit table does not change when the builder goes on, and the union has what any test reached", () -> {
            SiteTable t = SiteTable.sorted(List.of(draft(Point.Kind.RULE, "r", 1, 0), draft(Point.Kind.RULE, "r", 2, 0)));
            Hits.Builder b = new Hits.Builder(t, List.of("t1", "t2"));
            b.reach("t1", 1);
            Hits h = b.build();
            b.reach("t1", 2);
            eq(Hits.NOT_REACHED, h.level("t1", 2));
            b.reach("t2", 1);
            Hits h2 = b.build();
            BitSet u = h2.union();
            yes(u.get(1) && u.get(2), "id 1 is reached by both tests and stays in the union");
            eq(2, u.cardinality());
            BitSet own = h2.idsOf("t1");
            own.clear();
            eq(Hits.REACHED, h2.level("t1", 1));
        });

        c.finish();
    }
}
