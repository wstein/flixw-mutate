package dev.flixw.mutate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The run-time side of the solver hook (route (a), see {@link SolverPatch}). The patched
 * {@code Fixpoint3.Solver.Def$runSolverInternal.staticApply} returns {@link #solve} of its two arguments; {@code solve}
 * calls the original ({@code staticApply$orig}) itself. The class goes into the marker jar next to {@link Marker},
 * because generated code finds Java classes outside the JDK only through such a jar. The compiled hook functions of
 * {@code FlixwDatalog} (see {@link DatalogHook}) exist only in the program's own class loader, so {@code solve} finds
 * them through the class loader of the value it gets, and runs the trampolined result of a compiled function to its
 * value. It does nothing but call the original unless hits are recorded (the baseline run) or a Datalog mutant is
 * active (system property {@link #OP}). With a mutant it replaces the constraint set that enters the solver
 * ({@code flixwMutate}), and notes it in {@link #INVALID} if the solver or the program throws on a changed set. With
 * {@link #MODELS} on it records a fingerprint of the model of each solve ({@code flixwModel}).
 * The state is in system properties because this class is a second copy of the worker's.
 */
public final class Splice {
    static final String HOOK_PREFIX = "FlixwDatalog.Def$";
    static final String SOLVER_CLASS = "Fixpoint3.Solver.Def$runSolverInternal";
    /** The active Datalog mutant: {@code rule}, {@code fact} or {@code atom}; absent: none. {@code .key} and {@code .atom} say which. */
    static final String OP = "flixw.dlm.op";
    /** Set by {@code flixwMutate}: how many constraints of the last solve it changed. */
    static final String LAST = "flixw.dlm.last";
    /** Set when the solver or the program threw while solving a changed set: the exception. */
    static final String INVALID = "flixw.dlm.invalid";
    /** "1": the models of the solves are recorded. */
    static final String MODELS = "flixw.dlm.models";

    private Splice() { }

    public static Object solve(boolean withProv, Object datalog) throws Throwable {
        ClassLoader loader = datalog.getClass().getClassLoader();
        boolean record = System.getProperties().get(MarkerState.STATE_KEY) instanceof int[] state && state[1] != 0;
        boolean mutate = System.getProperty(OP) != null;
        if (!record && !mutate) return original(withProv, datalog);
        if (record) call(loader, "flixwHit", datalog);
        Object input = datalog;
        boolean changed = false;
        if (mutate) {
            System.setProperty(LAST, "0");
            input = value(call(loader, "flixwMutate", datalog));
            changed = !"0".equals(System.getProperty(LAST, "0"));
        }
        Object result;
        try {
            result = force(original(withProv, input));
        } catch (Throwable t) {
            if (changed) System.setProperty(INVALID, t.getClass().getName() + ": " + t.getMessage());
            throw t;
        }
        if ("1".equals(System.getProperty(MODELS))) call(loader, "flixwModel", value(result));
        return result;
    }

    /**
     * The original method of the solver of the program that {@code datalog}'s class belongs to: looked up once per class and
     * not per solve. The cache is a {@link ClassValue}, not a static map: the value is kept in the class itself, so it goes
     * with the program's class loader and cannot hold it. A static field of this class would hold the {@code Method} of the
     * program's class loader, and this class lives in the loader of the marker jar, which can outlive the program (a worker
     * that loads again). A failed lookup is not cached.
     */
    private static final Originals ORIGINAL = new Originals();

    /** A class of its own, not an anonymous one: it goes into the marker jar (see {@link MarkerJar}). */
    static final class Originals extends ClassValue<Method> {
        @Override
        protected Method computeValue(Class<?> type) {
            return lookup(type.getClassLoader());
        }
    }

    /** The cached {@code staticApply$orig} of the solver class in the class loader of {@code datalog}. */
    static Method solverOf(Class<?> datalog) {
        return ORIGINAL.get(datalog);
    }

    private static Method lookup(ClassLoader loader) {
        Method found = null;
        try {
            for (Method m : loader.loadClass(SOLVER_CLASS).getDeclaredMethods())
                if (m.getName().equals("staticApply$orig") && m.getParameterCount() == 2) found = m;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the solver class is not in the program: " + SOLVER_CLASS, e);
        }
        if (found == null) throw new IllegalStateException("the solver class has no staticApply$orig: " + SOLVER_CLASS);
        return found;
    }

    private static Object original(boolean withProv, Object datalog) throws Throwable {
        try {
            return solverOf(datalog.getClass()).invoke(null, withProv, datalog);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Calls the compiled hook function {@code name} with one argument and returns the forced result. */
    private static Object call(ClassLoader loader, String name, Object arg) throws Throwable {
        Class<?> hook;
        try {
            hook = loader.loadClass(HOOK_PREFIX + name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the Datalog hook is not in the program: " + HOOK_PREFIX + name, e);
        }
        for (Method m : hook.getDeclaredMethods())
            if (m.getName().equals("staticApply") && m.getParameterCount() == 1) {
                try {
                    return force(m.invoke(null, arg));
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        throw new IllegalStateException("the Datalog hook has no staticApply: " + HOOK_PREFIX + name);
    }

    /** The payload of a {@code dev.flix.runtime.Value$} (what a forced compiled function returns). */
    private static Object value(Object forced) throws ReflectiveOperationException {
        return forced.getClass().getField("o").get(forced);
    }

    /** Runs a trampolined result of a compiled Flix function until it is no longer a thunk. */
    private static Object force(Object r) throws Exception {
        while (isThunk(r)) {
            Method invoke = r.getClass().getMethod("invoke");
            invoke.setAccessible(true);
            r = invoke.invoke(r);
        }
        return r;
    }

    private static boolean isThunk(Object r) {
        for (Class<?> c = r.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("dev.flix.runtime.Thunk$")) return true;
            for (Class<?> i : c.getInterfaces()) if (i.getName().equals("dev.flix.runtime.Thunk$")) return true;
        }
        return false;
    }
}
