package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The schemata program of the fixture compiles, loads and passes its tests with the switch off. */
public final class SchemataCompileTest {
    public static void main(String[] args) throws Exception {
        Check c = new Check("SchemataCompileTest");
        Path fixture = SiteFinderTest.FIXTURE;
        Path calc = fixture.resolve("src/Calc.flix");
        Path testFile = fixture.resolve("test/TestCalc.flix");
        String text = Files.readString(calc);
        SiteFinder.Result found = SiteFinderTest.findInFixture(text, calc, List.of(testFile));
        Schemata.Rendered rendered = Schemata.render(text, found.sites().get(calc.toString()), 1);
        Path work = Check.tempDir("schemata-compile");
        Path renderedCalc = work.resolve("Calc.flix");
        Files.writeString(renderedCalc, rendered.text());
        Path module = work.resolve("FlixwMutant.flix");
        Files.writeString(module, Schemata.module());
        FlixApi api = new FlixApi(SchemataCompileTest.class.getClassLoader());
        Path markerJar = work.resolve("marker.jar");
        MarkerJar.write(markerJar);
        List<Path> jars = List.of(markerJar);
        int mutants = rendered.mutants().size();
        MarkerState.publish(mutants);
        MarkerState.set(0, false);

        c.test("the original program compiles and its tests pass", () -> {
            Object r = api.compileAndLoad(List.of(calc, testFile));
            yes(r instanceof FlixApi.Program, "compile failed: " + r);
            List<FlixApi.TestCase> tests = api.tests((FlixApi.Program) r);
            eq(12, tests.size());
            for (FlixApi.TestCase t : tests) if (!t.skip()) t.run().run();
        });

        c.test("the schemata program compiles; every mutant has a number", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile), jars);
            yes(r instanceof FlixApi.Program, "compile failed: " + r);
            eq(rendered.mutants().size(), rendered.mutants().get(rendered.mutants().size() - 1).id());
        });

        c.test("with the switch off the tests pass", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile), jars);
            for (FlixApi.TestCase t : api.tests((FlixApi.Program) r)) if (!t.skip()) t.run().run();
        });

        c.test("the right operand of and is evaluated only when needed (switch off)", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile), jars);
            List<FlixApi.TestCase> guarded = api.tests((FlixApi.Program) r).stream()
                .filter(t -> t.name().endsWith("testGuarded")).toList();
            eq(1, guarded.size());
            guarded.get(0).run().run();
        });

        c.test("a mutant number changes a result", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile), jars);
            List<FlixApi.TestCase> tests = api.tests((FlixApi.Program) r);
            Mutant sub = rendered.mutants().stream()
                .filter(m -> m.site().kind() == SiteKind.ADD && m.to().equals("-")).findFirst().orElseThrow();
            MarkerState.set(sub.id(), false);
            try {
                boolean failed = false;
                for (FlixApi.TestCase t : tests) {
                    if (t.skip()) continue;
                    try {
                        t.run().run();
                    } catch (RuntimeException | Error e) {
                        failed = true;
                    }
                }
                yes(failed, "no test failed with mutant " + sub.id());
            } finally {
                MarkerState.set(0, false);
            }
        });

        c.test("a recording run names the switches each test evaluates", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile), jars);
            java.util.Map<String, List<Integer>> hits = new java.util.TreeMap<>();
            MarkerState.set(0, true);
            try {
                for (FlixApi.TestCase t : api.tests((FlixApi.Program) r)) {
                    if (t.skip()) continue;
                    MarkerState.clear();
                    t.run().run();
                    hits.put(t.name(), MarkerState.hit());
                }
            } finally {
                MarkerState.set(0, false);
            }
            eq(11, hits.size());
            yes(!hits.get("TestCalc.testSumTo").isEmpty(), "no switch recorded for testSumTo");
            eq(List.of(), hits.get("TestCalc.testPaths"));
            java.util.Set<Integer> union = new java.util.TreeSet<>();
            hits.values().forEach(union::addAll);
            // All mutants but the one of the lazy operand that no test evaluates (testGuarded passes n != 0).
            eq(mutants - 1, union.size());
            yes(hits.get("TestCalc.testSumTo").size() < union.size(), "testSumTo must not reach everything");
        });

        c.test("a compile error is reported as messages", () -> {
            Path bad = work.resolve("Bad.flix");
            Files.writeString(bad, "mod Bad { pub def f(): Int32 = \"x\" }\n");
            Object r = api.compileAndLoad(List.of(bad));
            yes(r instanceof FlixApi.Failure f && !f.messages().isEmpty(), "expected failure, got " + r);
        });

        c.finish();
    }
}
