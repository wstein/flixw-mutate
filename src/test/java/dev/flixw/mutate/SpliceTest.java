package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * {@link Splice#solve}: without hits and mutant it only calls the original; the hit hook runs only while hits are
 * recorded (the baseline run); an active mutant replaces the constraint set, a throwing solver on a changed set is
 * noted as invalid, and the models are recorded on request. The solver and the hook functions are stand-ins with the
 * class names of the compiled program ({@code Fixpoint3.Solver.Def$runSolverInternal}, {@code FlixwDatalog.Def$...}),
 * compiled into a class loader of their own (on the class path of the test they would shadow the compiled program's
 * classes in the other suites).
 */
public final class SpliceTest {
    private static final String SOLVER = """
        package Fixpoint3.Solver;
        public final class Def$runSolverInternal {
            public static final class Val { public final Object o; public Val(Object o) { this.o = o; } }
            public static final class Datum { final String s; public Datum(String s) { this.s = s; } public String toString() { return s; } }
            public static final Object staticApply$orig(boolean withProv, Object datalog) {
                if (String.valueOf(datalog).contains("boom")) throw new IllegalStateException("solver failed");
                return new Val(new Datum("solved " + datalog + (withProv ? " with provenance" : "")));
            }
        }
        """;
    private static final String MUTATE = """
        package FlixwDatalog;
        public final class Def$flixwMutate {
            public static Object staticApply(Object datalog) {
                if (String.valueOf(datalog).startsWith("same")) return new Fixpoint3.Solver.Def$runSolverInternal.Val(datalog);
                System.setProperty("flixw.dlm.last", "1");
                return new Fixpoint3.Solver.Def$runSolverInternal.Val(new Fixpoint3.Solver.Def$runSolverInternal.Datum(datalog + "+m"));
            }
        }
        """;
    private static final String MODEL = """
        package FlixwDatalog;
        public final class Def$flixwModel {
            public static final java.util.List<String> SEEN = new java.util.ArrayList<>();
            public static Object staticApply(Object model) {
                SEEN.add(String.valueOf(model));
                return new Fixpoint3.Solver.Def$runSolverInternal.Val(0);
            }
        }
        """;

    private static ClassLoader standIns;
    /**
     * The directories of the stand-ins. The classes are loaded lazily and {@link Check#finish} exits the VM, so a shutdown
     * hook deletes them.
     */
    private static final List<Path> dirs = new java.util.ArrayList<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                cleanup();
            } catch (Exception e) {
                System.err.println("SpliceTest: stand-ins not deleted: " + e);
            }
        }));
    }

    static ClassLoader standIns() throws Exception {
        if (standIns == null) standIns = newStandIns();
        return standIns;
    }

    /** A new class loader with its own copy of the stand-ins. */
    static ClassLoader newStandIns() throws Exception {
        Path dir = Files.createTempDirectory("splice-test");
        dirs.add(dir);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        List<String> sources = List.of(SOLVER, MUTATE, MODEL);
        String[] names = {"Def$runSolverInternal", "Def$flixwMutate", "Def$flixwModel"};
        String[] args = new String[4 + sources.size()];
        args[0] = "-d";
        args[1] = classes.toString();
        args[2] = "-proc:none";
        args[3] = "-nowarn";
        for (int i = 0; i < sources.size(); i++) {
            Path f = dir.resolve("src").resolve(i + "/" + names[i] + ".java");
            Files.createDirectories(f.getParent());
            Files.writeString(f, sources.get(i));
            args[4 + i] = f.toString();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        eq(0, javac.run(null, null, null, args));
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, SpliceTest.class.getClassLoader());
    }

    static void cleanup() throws Exception {
        for (Path dir : dirs) {
            if (!Files.exists(dir)) continue;
            try (java.util.stream.Stream<Path> found = Files.walk(dir)) {
                found.sorted(java.util.Comparator.reverseOrder()).forEach(q -> q.toFile().delete());
            }
        }
    }

    /** A constraint set as the solver stand-in prints it, of a class that the stand-ins can be found through. */
    static Object datum(String s) throws Exception {
        return standIns().loadClass("Fixpoint3.Solver.Def$runSolverInternal$Datum").getConstructor(String.class).newInstance(s);
    }

    static String solve(boolean prov, String s) throws Exception {
        Object r;
        try {
            r = Splice.solve(prov, datum(s));
        } catch (Exception | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return String.valueOf(r.getClass().getField("o").get(r));
    }

    @SuppressWarnings("unchecked")
    static List<String> models() throws Exception {
        return (List<String>) standIns().loadClass("FlixwDatalog.Def$flixwModel").getField("SEEN").get(null);
    }

    static void reset() throws Exception {
        MarkerState.set(0, false);
        for (String k : new String[] {Splice.OP, Splice.LAST, Splice.INVALID, Splice.MODELS}) System.clearProperty(k);
        models().clear();
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("SpliceTest");
        MarkerState.publish(1);

        c.test("with no hits and no mutant the original runs on the argument, and no hook function is looked up", () -> {
            reset();
            eq("solved d", solve(false, "d"));
            eq("solved d with provenance", solve(true, "d"));
        });

        c.test("while hits are recorded a program without the hit hook fails loudly", () -> {
            reset();
            MarkerState.set(0, true);
            try {
                Throwable t = null;
                try {
                    solve(false, "d");
                } catch (IllegalStateException e) {
                    t = e;
                }
                yes(t != null && t.getMessage().contains("FlixwDatalog.Def$flixwHit"), "got " + t);
            } finally {
                reset();
            }
        });

        c.test("an active mutant: the original gets the changed set", () -> {
            reset();
            System.setProperty(Splice.OP, "rule");
            try {
                eq("solved d+m", solve(false, "d"));
            } finally {
                reset();
            }
        });

        c.test("a solver that throws on a changed set is noted as invalid, and the exception goes on", () -> {
            reset();
            System.setProperty(Splice.OP, "rule");
            try {
                throwsA(IllegalStateException.class, () -> solve(false, "boom"));
                String invalid = System.getProperty(Splice.INVALID);
                yes(invalid != null && invalid.contains("IllegalStateException: solver failed"), "got " + invalid);
            } finally {
                reset();
            }
        });

        c.test("a solver that throws on an unchanged set is not noted as invalid", () -> {
            reset();
            System.setProperty(Splice.OP, "rule");
            try {
                throwsA(IllegalStateException.class, () -> solve(false, "same boom"));
                eq(null, System.getProperty(Splice.INVALID));
            } finally {
                reset();
            }
        });

        c.test("the models are recorded only on request (and only while a mutant is active or hits are recorded)", () -> {
            reset();
            System.setProperty(Splice.OP, "rule");
            try {
                solve(false, "d");
                eq(0, models().size());
                System.setProperty(Splice.MODELS, "1");
                solve(false, "d");
                eq(List.of("solved d+m"), models());
            } finally {
                reset();
            }
        });

        c.test("the original is looked up once per class of the program, and each program gets its own", () -> {
            reset();
            Class<?> first = datum("a").getClass();
            Class<?> other = newStandIns().loadClass("Fixpoint3.Solver.Def$runSolverInternal$Datum");
            yes(first != other, "same class");
            java.lang.reflect.Method m = Splice.solverOf(first);
            // Without a cache every lookup returns a new Method object.
            yes(m == Splice.solverOf(first), "looked up again");
            yes(m == Splice.solverOf(datum("b").getClass()), "looked up again for a second value of the class");
            java.lang.reflect.Method n = Splice.solverOf(other);
            yes(n != m, "the Method of the other program is the cached one of the first");
            eq(first.getClassLoader(), m.getDeclaringClass().getClassLoader());
            eq(other.getClassLoader(), n.getDeclaringClass().getClassLoader());
            // The cache did not change what a solve does.
            eq("solved d", solve(false, "d"));
        });

        c.test("the directories of the stand-ins are deleted by cleanup", () -> {
            yes(!dirs.isEmpty(), "no stand-ins were made");
            cleanup();
            for (Path dir : dirs) yes(!Files.exists(dir), "left behind: " + dir);
        });

        c.finish();
    }
}
