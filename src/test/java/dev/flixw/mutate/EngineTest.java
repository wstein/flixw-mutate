package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** End to end: the engine on synthetic projects, with a real worker JVM and the pinned compiler. */
public final class EngineTest {
    static final Path JAVA = Path.of(System.getProperty("java.home"), "bin", "java");
    static final String CP = System.getProperty("java.class.path");

    static Engine.Config config(Path root, Long timeout) { return new Engine.Config(root, JAVA, CP, timeout); }

    static Path project(String src, String test) throws Exception {
        Path root = Files.createTempDirectory("engine-test");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("test"));
        if (src != null) Files.writeString(root.resolve("src/A.flix"), src);
        if (test != null) Files.writeString(root.resolve("test/TestA.flix"), test);
        return root;
    }

    static String line(Result r) {
        return r.outcome().name().toLowerCase() + " " + r.file() + ":" + r.position() + " " + r.mutant().from() + " -> " + r.mutant().to();
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("EngineTest");
        Path calc = SiteFinderTest.FIXTURE;

        c.test("the fixture: every planted outcome", () -> {
            Report r = Engine.run(config(calc, null));
            String f = "src/Calc.flix:";
            eq(List.of(
                "survived " + f + "4:13 < -> <=", "killed " + f + "4:13 < -> >=",
                "survived " + f + "4:33 > -> >=", "killed " + f + "4:33 > -> <=",
                "survived " + f + "7:13 <= -> <", "killed " + f + "7:13 <= -> >",
                "killed " + f + "7:28 + -> -",
                "killed " + f + "7:38 - -> +",
                "killed " + f + "11:13 <= -> <", "killed " + f + "11:13 <= -> >",
                "timeout " + f + "11:39 - -> +",
                "killed " + f + "11:49 + -> -",
                "killed " + f + "13:38 == -> !=",
                "killed " + f + "15:37 not -> (removed)",
                "killed " + f + "17:61 and -> or",
                "survived " + f + "17:62 >= -> >", "killed " + f + "17:62 >= -> <",
                "survived " + f + "17:76 <= -> <", "killed " + f + "17:76 <= -> >",
                "survived " + f + "19:41 < -> <=", "killed " + f + "19:41 < -> >=",
                "killed " + f + "23:54 * -> /",
                "killed " + f + "25:52 and -> or",
                "survived " + f + "25:58 > -> >=", "killed " + f + "25:58 > -> <=",
                "killed " + f + "25:58 / -> *",
                "killed " + f + "36:61 or -> and",
                "survived " + f + "36:61 < -> <=", "killed " + f + "36:61 < -> >=",
                "survived " + f + "36:71 > -> >=", "killed " + f + "36:71 > -> <="),
                r.results().stream().map(EngineTest::line).toList());
            eq(21, r.count(Outcome.KILLED));
            eq(9, r.count(Outcome.SURVIVED));
            eq(1, r.count(Outcome.TIMEOUT));
            eq(10, r.tests());
            eq(0, r.skippedSites());
        });

        c.test("the skipped test of the fixture is not counted or run", () -> eq(10, Engine.run(config(calc, null)).tests()));

        c.test("a candidate whose text has a comment in the operator gap is left out and counted", () -> {
            Path root = project("mod A {\n    pub def f(x: Int32): Int32 = x /* c */ + 1\n    pub def g(x: Int32): Int32 = x * 2\n}\n",
                "mod TestA { @Test def t(): Unit \\ Assert = { Assert.assertEq(expected = 2, A.f(1)); Assert.assertEq(expected = 4, A.g(2)) } }\n");
            Report r = Engine.run(config(root, null));
            eq(1, r.skippedSites());
            eq(1, r.results().size());
            eq("killed src/A.flix:3:34 * -> /", line(r.results().get(0)));
        });

        c.test("sources in two files get distinct mutant numbers", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n",
                "mod TestA { @Test def t(): Unit \\ Assert = { Assert.assertEq(expected = 2, A.f(1)); Assert.assertEq(expected = 4, B.g(2)) } }\n");
            Files.writeString(root.resolve("src/B.flix"), "mod B { pub def g(x: Int32): Int32 = x * 2 }\n");
            Report r = Engine.run(config(root, null));
            eq(2, r.results().size());
            eq(List.of(1, 2), r.results().stream().map(x -> x.mutant().id()).toList());
            eq(List.of("killed src/A.flix:1:38 + -> -", "killed src/B.flix:1:38 * -> /"), r.results().stream().map(EngineTest::line).toList());
        });

        c.test("a killed mutant names the failing test", () -> {
            Report r = Engine.run(config(calc, null));
            Result first = r.results().get(1);
            eq(Outcome.KILLED, first.outcome());
            yes(first.detail().contains("TestCalc.testClamp"), first.detail());
        });

        c.test("a project without src has no mutants and needs no worker", () -> {
            Path root = Files.createTempDirectory("engine-empty");
            Report r = Engine.run(new Engine.Config(root, Path.of("/nonexistent/java"), "nowhere", null));
            eq(0, r.results().size());
        });

        c.test("sources without sites give an empty report", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x }\n",
                "mod TestA { @Test def t(): Unit \\ Assert = Assert.assertEq(expected = 1, A.f(1)) }\n");
            eq(0, Engine.run(config(root, null)).results().size());
        });

        c.test("tests that fail without a mutation stop the run", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n",
                "mod TestA { @Test def t(): Unit \\ Assert = Assert.assertEq(expected = 5, A.f(1)) }\n");
            Engine.Failure f = null;
            try {
                Engine.run(config(root, null));
            } catch (Engine.Failure e) {
                f = e;
            }
            yes(f != null && f.getMessage().contains("fail without a mutation") && f.getMessage().contains("TestA.t"),
                String.valueOf(f));
        });

        c.test("a project that does not compile stops the run", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = \"x\" }\n", "mod TestA { }\n");
            Engine.Failure f = null;
            try {
                Engine.run(config(root, null));
            } catch (Engine.Failure e) {
                f = e;
            }
            yes(f != null && f.getMessage().contains("does not compile"), String.valueOf(f));
        });

        c.test("a project without tests stops the run", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n", "mod TestA { }\n");
            Engine.Failure f = null;
            try {
                Engine.run(config(root, null));
            } catch (Engine.Failure e) {
                f = e;
            }
            yes(f != null && f.getMessage().contains("no tests"), String.valueOf(f));
        });

        c.test("a missing class path entry is named before the worker starts", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n", "mod TestA { }\n");
            Engine.Failure f = null;
            try {
                Engine.run(new Engine.Config(root, JAVA, CP + java.io.File.pathSeparator + "/nonexistent/flix.jar", null));
            } catch (Engine.Failure e) {
                f = e;
            }
            yes(f != null && f.getMessage().contains("/nonexistent/flix.jar") && f.getMessage().contains("does not exist"),
                String.valueOf(f));
        });

        c.test("a worker that cannot start is a failure", () -> {
            Path root = project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n", "mod TestA { }\n");
            throwsA(Engine.Failure.class, () -> Engine.run(new Engine.Config(root, Path.of("/nonexistent/java"), CP, null)));
        });

        c.finish();
    }
}
