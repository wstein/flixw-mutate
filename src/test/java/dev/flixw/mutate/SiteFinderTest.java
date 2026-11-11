package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SiteFinderTest {
    static final Path FIXTURE = Path.of(System.getenv("FLIXW_TEST_FIXTURES")).resolve("calc").toAbsolutePath();

    /** Finds the sites of the fixture's src file; everything else in the project is only compiled. */
    static SiteFinder.Result findInFixture(String calcText, Path calc, List<Path> others) throws Exception {
        FlixApi api = new FlixApi(SiteFinderTest.class.getClassLoader());
        List<Path> all = new ArrayList<>(List.of(calc));
        all.addAll(others);
        FlixApi.Checked checked = api.check(all);
        if (checked.failure() != null) throw new AssertionError("fixture does not compile: " + checked.failure().messages());
        return SiteFinder.find(api, checked.root(), Map.of(calc.toString(), calcText));
    }

    static List<String> describe(String text, List<Site> sites) {
        return sites.stream().map(s -> Schemata.position(text, s.whole().start()) + " " + s.kind() + " "
            + text.substring(s.whole().start(), s.whole().end())).toList();
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("SiteFinderTest");
        Path calc = FIXTURE.resolve("src/Calc.flix");
        String text = Files.readString(calc);
        SiteFinder.Result r = findInFixture(text, calc, List.of(FIXTURE.resolve("test/TestCalc.flix")));

        c.test("sites of the fixture, in rewrite order, with their source text", () -> eq(List.of(
            "4:13 LESS x < lo",
            "4:33 GREATER x > hi",
            "7:13 LESS_EQUAL n <= 0",
            "7:28 ADD n + sumTo(n - 1)",
            "7:38 SUBTRACT n - 1",
            "10:13 LESS_EQUAL n <= 0",
            "10:36 SUBTRACT n - 1",
            "10:43 ADD acc + 1",
            "12:38 EQUAL Int32.modulo(n, 2) == 0",
            "14:37 NOT not isEven(n)",
            "16:61 AND (x >= lo) and (x <= hi)",
            "16:62 GREATER_EQUAL x >= lo",
            "16:76 LESS_EQUAL x <= hi",
            "18:41 LESS x < 0",
            "22:54 MULTIPLY a * b",
            "24:52 AND a and 10 / n > 1",
            "24:58 GREATER 10 / n > 1",
            "24:58 DIVIDE 10 / n",
            "35:61 OR x < lo or x > hi",
            "35:61 LESS x < lo",
            "35:71 GREATER x > hi",
            "44:9 AND a and (if (n == 0) System.getProperty(\"\") == \"x\" else true)",
            "44:20 EQUAL n == 0",
            "44:28 EQUAL System.getProperty(\"\") == \"x\""), describe(text, r.sites().get(calc.toString()))));

        c.test("only the given file has sites", () -> eq(List.of(calc.toString()), List.copyOf(r.sites().keySet())));

        c.test("string concatenation and negation are not sites", () -> {
            for (Site s : r.sites().get(calc.toString())) {
                String t = text.substring(s.whole().start(), s.whole().end());
                Check.yes(!t.contains("\"hi \""), "string +: " + t);
                Check.yes(!t.startsWith("-"), "negation: " + t);
            }
        });

        c.test("no candidate was left out in the fixture", () -> eq(0, r.skipped()));

        c.test("a file that is not given has no sites", () -> {
            SiteFinder.Result none = findInFixture(text, calc, List.of(FIXTURE.resolve("test/TestCalc.flix")));
            eq(false, none.sites().isEmpty());
            FlixApi api = new FlixApi(SiteFinderTest.class.getClassLoader());
            FlixApi.Checked checked = api.check(List.of(calc, FIXTURE.resolve("test/TestCalc.flix")));
            eq(Map.of(), SiteFinder.find(api, checked.root(), Map.of("elsewhere.flix", "")).sites());
        });

        c.test("offset: 1-based line and column", () -> {
            eq(0, SiteFinder.offset("ab\ncd\n", 1, 1));
            eq(4, SiteFinder.offset("ab\ncd\n", 2, 2));
            eq(-1, SiteFinder.offset("ab\ncd\n", 9, 1));
        });

        c.finish();
    }
}
