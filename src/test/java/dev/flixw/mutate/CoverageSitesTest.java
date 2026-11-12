package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Both kinds of site in one program: the mutation sites (switch numbers of the operators) and the region sites
 * of the coverage spike (function entries, branches, lambda bodies, the lines they own), all rows of one site table.
 * Object: the fixture of the coverage spike, whose line annotations, {@code expected-functions.tsv} and
 * {@code expected-branches.tsv} are the truth, plus per-test truth written by hand from the test files; the calc
 * and the spike fixture for "regions change no mutant and no report line"; the wide fixture of the selection spike
 * for the mutants and the test selection.
 */
public final class CoverageSitesTest {
    static final Path SPIKE = Path.of(System.getenv("FLIXW_TEST_COVERAGE_SPIKE")).toAbsolutePath();
    static final Path FIXTURE = SPIKE.resolve("fixture");
    static final Path CALC = SiteFinderTest.FIXTURE;
    static final Path WIDE = SelectionTest.WIDE;

    static Engine.Config config(Path root) { return new Engine.Config(root, EngineTest.JAVA, EngineTest.CP, null); }

    /** The annotated lines of the fixture as {@code file:line:S}, without Limits.flix (its lines are the known limits). */
    static List<String> wantLines() throws Exception {
        List<String> out = new ArrayList<>();
        for (String f : List.of("Colors.flix", "Grade.flix")) {
            String[] ls = Files.readString(FIXTURE.resolve("src").resolve(f)).split("\n", -1);
            for (int i = 0; i < ls.length; i++) {
                String l = ls[i].stripTrailing();
                int k = l.lastIndexOf("// ");
                if (k >= 0 && l.length() == k + 4 && "HMP".indexOf(l.charAt(k + 3)) >= 0)
                    out.add("src/" + f + ":" + (i + 1) + ":" + l.charAt(k + 3));
            }
        }
        return sorted(out);
    }

    /** Rows of a truth file under the fixture, without the header, with the file column as in the site table. */
    static List<String> truth(String name) throws Exception {
        List<String> all = Files.readAllLines(FIXTURE.resolve(name));
        return sorted(all.subList(1, all.size()).stream().map(r -> "src/" + r).collect(Collectors.toList()));
    }

    static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        Collections.sort(out);
        return out;
    }

    static List<String> without(List<String> rows, String filePrefix) {
        return rows.stream().filter(r -> !r.startsWith(filePrefix)).collect(Collectors.toList());
    }

    /** {@code test line} rows of the kinds, as the views give them, sorted. */
    static List<String> reached(Coverage c, Point.Kind... kinds) {
        List<String> out = new ArrayList<>();
        for (Point.Kind k : kinds) out.addAll(Views.reached(c.sites(), c.hits(), k));
        return sorted(out);
    }

    /** What the tests of the fixture reach, written from the test files: the entries and branches each test runs. */
    static List<String> wantReached() {
        List<String> out = new ArrayList<>();
        String[][] rows = {
            // test, line... (function entry, then, else and arm sites; the line is the line of the def or construct)
            {"TestGrade.testClassify", "4", "5", "5", "7"},          // entry; if: then, else; else-if: then
            {"TestGrade.testName", "12", "12", "12"},                 // entry; arms 0 and 2
            {"TestGrade.testHalf", "18", "18", "18"},                 // entry; arms 0 and 2
            {"TestGrade.testTotalEmpty", "24"},                       // entry; the lambda is not called
            {"TestGrade.testFact", "27", "28", "28"},                 // entry; then, else
            {"TestGrade.testStats", "30"},
            {"TestGrade.testAbs", "35", "35"},                        // entry; else
            {"TestGrade.testGuarded", "40", "41"},                    // entry; else
            {"TestGrade.testColor", "7", "7"},                        // Colors: entry; arm Red
            {"TestLimits.testBothFalse", "6"},                        // check is not called
            {"TestLimits.testEachSumEmpty", "8"},
        };
        for (String[] r : rows) for (int i = 1; i < r.length; i++) out.add(r[0] + "\t" + r[i]);
        return sorted(out);
    }

    /** The mutant rows of a table without their ids: file, line and span, in table order. */
    static List<String> mutantRows(SiteTable t) {
        List<String> out = new ArrayList<>();
        for (Point p : t.points())
            if (p.kind() == Point.Kind.MUTANT) out.add(p.file() + ":" + p.line() + ":" + p.span());
        return out;
    }

    static List<String> describe(Session s) {
        List<String> out = new ArrayList<>();
        for (Mutant m : s.mutants()) out.add(s.file(m) + ":" + s.position(m) + " " + m.from() + " -> " + m.to());
        return out;
    }

    /** The report without the line that carries the baseline time (the timeout is ten times it). */
    static String stable(Report r) {
        return r.format().lines().filter(l -> !l.startsWith("tests: ")).collect(Collectors.joining("\n"));
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("CoverageSitesTest");

        c.test("the table of one run gives the lines, functions and branches of the truth files", () -> {
            try (Session s = Session.open(config(FIXTURE).withRegions())) {
                Coverage cov = s.coverage();
                BitSet all = cov.hits().union();
                eq(wantLines(), sorted(without(Views.lines(cov.sites(), all), "src/Limits.flix:")));
                eq(truth("expected-functions.tsv"), sorted(without(Views.functions(cov.sites(), all), "src/Limits.flix\t")));
                eq(truth("expected-branches.tsv"), sorted(without(Views.branches(cov.sites(), all), "src/Limits.flix\t")));
                // the known limits: the tool reads H where part of the line did not run
                List<String> lines = Views.lines(cov.sites(), all);
                yes(lines.contains("src/Limits.flix:6:H") && lines.contains("src/Limits.flix:10:H"), "limits " + lines);
            }
        });

        c.test("the regions each test reaches are the ones its test file runs", () -> {
            try (Session s = Session.open(config(FIXTURE).withRegions())) {
                eq(wantReached(), reached(s.coverage(), Point.Kind.FUNCTION, Point.Kind.THEN, Point.Kind.ELSE, Point.Kind.ARM));
            }
        });

        c.test("one id space: mutants and regions are numbered together and the marker covers all ids", () -> {
            try (Session s = Session.open(config(FIXTURE).withRegions())) {
                SiteTable t = s.coverage().sites();
                long mutants = t.points().stream().filter(p -> p.kind() == Point.Kind.MUTANT).count();
                long regions = t.points().stream().filter(p -> p.kind() == Point.Kind.FUNCTION).count();
                eq((long) s.mutants().size(), mutants);
                yes(regions > 0, "no FUNCTION rows in the table");
                for (Mutant m : s.mutants()) eq(Point.Kind.MUTANT, t.get(m.id()).kind());
                // a test reaches a mutant's site and a region of the same function in the same run
                Mutant any = s.mutants().get(0);
                yes(!s.coverage().testsFor(any.id()).isEmpty(), "the first mutant is reached by no test");
            }
        });

        for (Path root : List.of(CALC, FIXTURE, WIDE)) {
            String name = root.getFileName().toString();

            c.test("regions change no mutant and no test selection (" + name + ")", () -> {
                try (Session plain = Session.open(config(root)); Session with = Session.open(config(root).withRegions())) {
                    eq(describe(plain), describe(with));
                    eq(mutantRows(plain.coverage().sites()), mutantRows(with.coverage().sites()));
                    for (int i = 0; i < plain.mutants().size(); i++)
                        eq(plain.coverage().testsFor(plain.mutants().get(i).id()), with.coverage().testsFor(with.mutants().get(i).id()));
                    eq(plain.testCount(), with.testCount());
                }
            });

            if (root.equals(WIDE)) continue; // the reports of the wide fixture are compared by the run of the author (PR text)
            c.test("regions change no line of the report, with and without selection (" + name + ")", () -> {
                for (boolean selection : new boolean[] {true, false}) {
                    Engine.Config base = selection ? config(root) : config(root).withoutSelection();
                    Report plain = Engine.run(base);
                    Report with = Engine.run(selection ? base.withRegions() : base.withoutSelection().withRegions());
                    yes(!plain.results().isEmpty(), "no mutants");
                    eq(plain.tests(), with.tests());
                    eq(stable(plain), stable(with));
                }
            });
        }

        c.finish();
    }
}
