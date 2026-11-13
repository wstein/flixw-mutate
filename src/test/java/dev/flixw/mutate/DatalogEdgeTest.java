package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.yes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * The edges of the Datalog hits ({@link DatalogHitsTest} has the truth of the spike): two hooks that lie, a rule that two
 * defs share, and a program without a solver.
 */
public final class DatalogEdgeTest {
    static final Path FIXTURE = DatalogHitsTest.FIXTURE;

    /** A class directory with a replacement of {@link Splice}, which goes first on the worker class path. */
    static Path saboteur(String body) throws Exception {
        Path dir = Files.createTempDirectory("sabotage-dl");
        Path src = dir.resolve("src/dev/flixw/mutate/Splice.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package dev.flixw.mutate;\npublic final class Splice {\n    private Splice() { }\n"
            + "    public static Object solve(boolean withProv, Object datalog) throws Throwable {\n" + body + "\n"
            + "        for (java.lang.reflect.Method m : datalog.getClass().getClassLoader().loadClass(\"Fixpoint3.Solver.Def$runSolverInternal\").getDeclaredMethods())\n"
            + "            if (m.getName().equals(\"staticApply$orig\")) {\n"
            + "                try { return m.invoke(null, withProv, datalog); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }\n"
            + "            }\n"
            + "        throw new IllegalStateException(\"no original\");\n    }\n}\n");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        eq(0, javac.run(null, null, null, "-d", classes.toString(), src.toString()));
        return classes;
    }

    static Engine.Config sabotaged(Path classes) {
        return new Engine.Config(FIXTURE, EngineTest.JAVA, classes + java.io.File.pathSeparator + EngineTest.CP, null).withDatalog();
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("DatalogEdgeTest");

        c.test("sabotage: a hook that writes no marks fails the comparison at every part of it", () -> {
            try (Session s = Session.open(sabotaged(saboteur("")))) {
                List<String> d = DatalogHitsTest.deviations(s.coverage());
                eq(3, d.size());
                yes(d.get(0).startsWith("rules: ") && d.get(0).endsWith("got []"), d.get(0));
                yes(d.get(1).startsWith("facts: ") && d.get(1).endsWith("got {}"), d.get(1));
                yes(d.get(2).contains("got [TestNet.testDouble, TestNet.testEvens, TestNet.testReachable]"), d.get(2));
            }
        });

        c.test("sabotage: a hook that reports every rule and fact as solved fails the comparison", () -> {
            String all = String.join("\\n", List.of("Reach<-Start", "Reach<-Reach,Edge", "Dead<-Reach,Edge", "Lone<-Edge,Reach", "$Result<-Reach",
                "Even<-Num", "$Result<-Even"));
            String body = "System.setProperty(\"flixw.dl.solves\", \"1\");"
                + "String[] rules = \"" + all + "\".split(\"\\n\");"
                + "System.setProperty(\"flixw.dl.0.rules\", String.valueOf(rules.length));"
                + "for (int i = 0; i < rules.length; i++) System.setProperty(\"flixw.dl.0.rule.\" + i, rules[i]);"
                + "String[] facts = {\"Edge(1, 2).\", \"Edge(2, 3).\", \"Edge(7, 8).\", \"Start(1).\", \"Tag(5).\", \"Num(2).\", \"Num(3).\", \"Num(4).\"};"
                + "System.setProperty(\"flixw.dl.0.facts\", String.valueOf(facts.length));"
                + "for (int i = 0; i < facts.length; i++) System.setProperty(\"flixw.dl.0.fact.\" + i, facts[i]);";
            try (Session s = Session.open(sabotaged(saboteur(body)))) {
                List<String> d = DatalogHitsTest.deviations(s.coverage());
                eq(2, d.size());
                yes(d.get(0).startsWith("rules: ") && d.get(0).contains("TestNet.testEvens\t9"), d.get(0));
                yes(d.get(1).startsWith("facts: ") && d.get(1).contains("TestNet.testEvens=8") && d.get(1).contains("TestNet.testReachable=8"), d.get(1));
            }
        });

        c.test("a rule that a def of the test and another def share: the test reaches its own, a test elsewhere reaches both (limit)", () -> {
            Path p = EngineTest.project("""
                mod A {
                    pub def two(): Vector[Int32] =
                        let p = #{
                            N(2).
                            R(x) :- N(x).
                        };
                        query p select x from R(x)

                    @Test
                    def testOne(): Unit \\ Assert =
                        let p = #{
                            N(1).
                            R(x) :- N(x).
                        };
                        Assert.assertEq(expected = 1 :: Nil, Vector.toList(query p select x from R(x)))
                }
                """, """
                mod TestA {
                    @Test
                    def testTwo(): Unit \\ Assert = Assert.assertEq(expected = 2 :: Nil, Vector.toList(A.two()))
                }
                """);
            try (Session s = Session.open(new Engine.Config(p, EngineTest.JAVA, EngineTest.CP, null).withDatalog())) {
                Coverage cov = s.coverage();
                // the rule R(x) :- N(x) is at line 5 (in two) and 13 (in testOne), the query rules at 7 and 15; N(2) is at line 4, N(1) at 12
                eq(List.of("A.testOne\t13", "A.testOne\t15"), CoverageSitesTest.reached(cov, Point.Kind.RULE).stream().filter(r -> r.startsWith("A.")).toList());
                eq(List.of("TestA.testTwo\t13", "TestA.testTwo\t15", "TestA.testTwo\t5", "TestA.testTwo\t7"),
                    CoverageSitesTest.reached(cov, Point.Kind.RULE).stream().filter(r -> r.startsWith("TestA.")).toList());
                // the facts have different text: each test reaches its own
                eq(List.of("A.testOne\t12", "TestA.testTwo\t4"), CoverageSitesTest.reached(cov, Point.Kind.FACT));
            }
        });

        c.test("a program that never solves has no solver class: the run works and reaches nothing", () -> {
            Path p = EngineTest.project("mod A { pub def f(x: Int32): Int32 = x + 1 }\n", "mod TestA { use Assert.assertEq;\n @Test def t(): Unit \\ Assert = assertEq(expected = 2, A.f(1)) }\n");
            List<String> lines = new java.util.ArrayList<>();
            try (Session s = Session.open(new Engine.Config(p, EngineTest.JAVA, EngineTest.CP, null, lines::add).withDatalog())) {
                eq(1, s.mutants().size());
                for (Point pt : s.coverage().sites().points()) yes(pt.kind() == Point.Kind.MUTANT, "row " + pt);
            }
            // the sources have no Datalog site: there is nothing to warn about
            eq(List.of(), lines.stream().filter(l -> l.startsWith("warning")).toList());
        });

        c.test("a set composed with injected facts reaches the solver as a join and is recorded", () -> {
            Path p = EngineTest.project("""
                mod A {
                    pub def two(ns: List[Int32]): Int32 =
                        let p = #{
                            R(x) :- N(x).
                        };
                        let q = inject ns into N/1;
                        let m = solve (p <+> q);
                        Vector.length(query m select x from R(x))
                }
                """, """
                mod TestA {
                    @Test
                    def t(): Unit \\ Assert = Assert.assertEq(expected = 2, A.two(1 :: 2 :: Nil))
                }
                """);
            try (Session s = Session.open(new Engine.Config(p, EngineTest.JAVA, EngineTest.CP, null).withDatalog())) {
                // the rule at line 4; the query over the model (line 8) is not a solve of the program
                eq(List.of("TestA.t\t4"), CoverageSitesTest.reached(s.coverage(), Point.Kind.RULE, Point.Kind.FACT));
            }
        });

        c.test("a solve of a set of facts only is recorded: its facts are reached", () -> {
            Path p = EngineTest.project("""
                mod A {
                    pub def f(): Int32 =
                        let p = #{
                            F(1).
                            F(2).
                        };
                        let m = solve p;
                        Vector.length(query m select x from F(x))
                }
                """, """
                mod TestA {
                    @Test
                    def t(): Unit \\ Assert = Assert.assertEq(expected = 2, A.f())
                }
                """);
            List<String> lines = new java.util.ArrayList<>();
            try (Session s = Session.open(new Engine.Config(p, EngineTest.JAVA, EngineTest.CP, null, lines::add).withDatalog())) {
                eq(List.of("TestA.t\t4", "TestA.t\t5"), CoverageSitesTest.reached(s.coverage(), Point.Kind.FACT));
            }
            // the program has a solver class: no warning
            eq(List.of(), lines.stream().filter(l -> l.startsWith("warning")).toList());
        });

        c.test("a Datalog set that the program never solves: a warning that the solver class is missing, no rule reached", () -> {
            Path p = EngineTest.project("""
                mod A {
                    pub def unused(): Vector[Int32] =
                        let p = #{
                            N(1).
                            R(x) :- N(x).
                        };
                        query p select x from R(x)

                    pub def f(x: Int32): Int32 = x + 1
                }
                """, """
                mod TestA {
                    @Test
                    def t(): Unit \\ Assert = Assert.assertEq(expected = 2, A.f(1))
                }
                """);
            List<String> lines = new java.util.ArrayList<>();
            try (Session s = Session.open(new Engine.Config(p, EngineTest.JAVA, EngineTest.CP, null, lines::add).withDatalog())) {
                eq(List.of(), CoverageSitesTest.reached(s.coverage(), Point.Kind.RULE, Point.Kind.FACT));
                yes(s.coverage().sites().points().stream().anyMatch(pt -> pt.kind() == Point.Kind.RULE), "no rule row");
                // a second load (a new worker program, as after a timeout) does not warn again
                s.freshBaseline(s.coverage().tests());
            }
            eq(1L, lines.stream().filter(l -> l.startsWith("warning: datalog: ") && l.contains("solver")).count());
        });

        c.finish();
    }
}
