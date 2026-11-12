package dev.flixw.mutate;

import static dev.flixw.mutate.Check.eq;
import static dev.flixw.mutate.Check.throwsA;
import static dev.flixw.mutate.Check.yes;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The class-file edit of the solver hook, on a class of this test with the shape of the solver's method: a
 * static method {@code (Z<ref>)<ref>}. The edit must keep what the method does, call the hook first with the
 * second argument, and leave a second method of the same name alone.
 */
public final class SolverPatchTest {
    /** The calls the hook saw, in order, with what the original returned afterwards. */
    public static final List<String> LOG = new ArrayList<>();

    /** The class that is edited. */
    public static final class Target {
        private Target() { }

        public static final Object staticApply(boolean flag, Object datalog) {
            LOG.add("orig " + flag + " " + datalog);
            return "result of " + datalog;
        }

        /** Another method of the same name: it must keep its name. */
        public static int staticApply(int x) { return x + 1; }

        /** Constants that take two slots of the constant pool. */
        public static long bigLong() { return 123456789012345L; }

        public static double bigDouble() { return 1.5e300; }

        public static String text() { return "text after the two-slot constants"; }

        public static boolean twice(Object o) { return staticApply(true, o) != null && staticApply(false, o) != null; }
    }

    /** The hook. */
    public static void hook(Object datalog) { LOG.add("hook " + datalog); }

    static final String TARGET = SolverPatchTest.class.getName().replace('.', '/') + "$Target";
    static final String HOOK = SolverPatchTest.class.getName().replace('.', '/');
    static final String DESC = "(ZLjava/lang/Object;)Ljava/lang/Object;";

    static byte[] original() throws Exception {
        try (InputStream in = SolverPatchTest.class.getClassLoader().getResourceAsStream(TARGET + ".class")) {
            return in.readAllBytes();
        }
    }

    static final class Loader extends ClassLoader {
        Loader() { super(SolverPatchTest.class.getClassLoader()); }

        Class<?> define(byte[] bytes) { return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length); }
    }

    static Class<?> patched() throws Exception {
        return new Loader().define(SolverPatch.wrap(original(), "staticApply", DESC, HOOK, "hook"));
    }

    public static void main(String[] args) throws Exception {
        Check c = new Check("SolverPatchTest");

        c.test("the hook runs first with the second argument, then the original, and its result is returned", () -> {
            LOG.clear();
            Object r = patched().getMethod("staticApply", boolean.class, Object.class).invoke(null, true, "d1");
            eq("result of d1", r);
            eq(List.of("hook d1", "orig true d1"), LOG);
        });

        c.test("the original is kept under the new name", () -> {
            LOG.clear();
            Class<?> t = patched();
            Object r = t.getMethod("staticApply$orig", boolean.class, Object.class).invoke(null, false, "d2");
            eq("result of d2", r);
            eq(List.of("orig false d2"), LOG);
        });

        c.test("a call inside the class reaches the new method", () -> {
            LOG.clear();
            eq(true, patched().getMethod("twice", Object.class).invoke(null, "d3"));
            eq(List.of("hook d3", "orig true d3", "hook d3", "orig false d3"), LOG);
        });

        c.test("a method of the same name with another descriptor is not touched", () -> {
            Class<?> t = patched();
            eq(8, t.getMethod("staticApply", int.class).invoke(null, 7));
            boolean renamed = false;
            for (Method m : t.getDeclaredMethods()) if (m.getName().equals("staticApply$orig") && m.getParameterCount() == 1) renamed = true;
            yes(!renamed, "the int method was renamed");
        });

        c.test("the result is a class the verifier accepts, with the modifiers of the original", () -> {
            Class<?> t = patched();
            Method m = t.getMethod("staticApply", boolean.class, Object.class);
            eq(Target.class.getMethod("staticApply", boolean.class, Object.class).getModifiers(), m.getModifiers());
        });

        c.test("a class with long and double constants is edited correctly (they take two slots of the pool)", () -> {
            Class<?> t = patched();
            eq(123456789012345L, t.getMethod("bigLong").invoke(null));
            eq(1.5e300, t.getMethod("bigDouble").invoke(null));
            eq("text after the two-slot constants", t.getMethod("text").invoke(null));
            LOG.clear();
            eq("result of x", t.getMethod("staticApply", boolean.class, Object.class).invoke(null, true, "x"));
            eq(List.of("hook x", "orig true x"), LOG);
        });

        c.test("a method that is not there, a wrong descriptor and a class that is already wrapped are refused", () -> {
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(original(), "nothing", DESC, HOOK, "hook"));
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(original(), "staticApply", "(I)I", HOOK, "hook"));
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(original(), "staticApply", DESC.replace("Object", "String"), HOOK, "hook"));
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, "staticApply", DESC, HOOK, "hook"));
            byte[] once = SolverPatch.wrap(original(), "staticApply", DESC, HOOK, "hook");
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(once, "staticApply", DESC, HOOK, "hook"));
        });

        c.test("an instance method of that name is refused", () -> {
            // the class of this test has no static method with this name and shape: use a method that is not static
            throwsA(SolverPatch.NotAsExpected.class, () -> SolverPatch.wrap(
                SolverPatchTest.class.getClassLoader().getResourceAsStream(HOOK + "$Plain.class").readAllBytes(), "run", DESC, HOOK, "hook"));
        });

        c.finish();
    }

    /** A class with an instance method of the shape, for the refusal. */
    public static final class Plain {
        public Object run(boolean flag, Object datalog) { return datalog; }
    }
}
