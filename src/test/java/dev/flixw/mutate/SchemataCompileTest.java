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
        Path work = Files.createTempDirectory("schemata-compile");
        Path renderedCalc = work.resolve("Calc.flix");
        Files.writeString(renderedCalc, rendered.text());
        Path module = work.resolve("FlixwMutant.flix");
        Files.writeString(module, Schemata.module());
        FlixApi api = new FlixApi(SchemataCompileTest.class.getClassLoader());

        c.test("the original program compiles and its tests pass", () -> {
            Object r = api.compileAndLoad(List.of(calc, testFile));
            yes(r instanceof FlixApi.Program, "compile failed: " + r);
            List<FlixApi.TestCase> tests = api.tests((FlixApi.Program) r);
            eq(9, tests.size());
            for (FlixApi.TestCase t : tests) t.run().run();
        });

        System.clearProperty("flixw.mutant");
        c.test("the schemata program compiles; every mutant has a number", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile));
            yes(r instanceof FlixApi.Program, "compile failed: " + r);
            eq(rendered.mutants().size(), rendered.mutants().get(rendered.mutants().size() - 1).id());
        });

        c.test("with the switch off the tests pass", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile));
            for (FlixApi.TestCase t : api.tests((FlixApi.Program) r)) t.run().run();
        });

        c.test("a mutant number changes a result", () -> {
            Object r = api.compileAndLoad(List.of(renderedCalc, module, testFile));
            List<FlixApi.TestCase> tests = api.tests((FlixApi.Program) r);
            Mutant sub = rendered.mutants().stream()
                .filter(m -> m.site().kind() == SiteKind.ADD && m.to().equals("-")).findFirst().orElseThrow();
            System.setProperty("flixw.mutant", String.valueOf(sub.id()));
            try {
                boolean failed = false;
                for (FlixApi.TestCase t : tests) {
                    try {
                        t.run().run();
                    } catch (RuntimeException | Error e) {
                        failed = true;
                    }
                }
                yes(failed, "no test failed with mutant " + sub.id());
            } finally {
                System.clearProperty("flixw.mutant");
            }
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
