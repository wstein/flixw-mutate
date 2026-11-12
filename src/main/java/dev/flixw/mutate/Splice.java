package dev.flixw.mutate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The run-time side of the solver hook (route (a), see {@link SolverPatch}). The patched
 * {@code Fixpoint3.Solver.Def$runSolverInternal.staticApply} calls {@link #before} with the Datalog value that enters
 * the solver. The class goes into the marker jar next to {@link Marker}, because generated code finds Java classes
 * outside the JDK only through such a jar. The compiled hook function {@code FlixwDatalog.flixwHit} (see
 * {@link DatalogHook}) exists only in the program's own class loader, so {@code before} finds it through the class
 * loader of the value it gets, and runs the trampolined result of the compiled function to its value. It does
 * nothing unless hits are recorded (the baseline run).
 */
public final class Splice {
    static final String HOOK_CLASS = "FlixwDatalog.Def$flixwHit";

    private Splice() { }

    public static void before(Object datalog) throws Exception {
        // The hook records for the baseline run only; in the runs of the mutants it would write system properties
        // that nobody reads or clears (the state array is the one of Marker, published by the worker).
        if (!(System.getProperties().get(MarkerState.STATE_KEY) instanceof int[] state) || state[1] == 0) return;
        Class<?> hook;
        try {
            hook = datalog.getClass().getClassLoader().loadClass(HOOK_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the Datalog hook is not in the program: " + HOOK_CLASS, e);
        }
        for (Method m : hook.getDeclaredMethods())
            if (m.getName().equals("staticApply") && m.getParameterCount() == 1) {
                try {
                    force(m.invoke(null, datalog));
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) throw ex;
                    if (e.getCause() instanceof Error er) throw er;
                    throw e;
                }
                return;
            }
        throw new IllegalStateException("the Datalog hook has no staticApply: " + HOOK_CLASS);
    }

    /** Runs a trampolined result of a compiled Flix function until it is no longer a thunk. */
    private static void force(Object r) throws Exception {
        while (isThunk(r)) {
            Method invoke = r.getClass().getMethod("invoke");
            invoke.setAccessible(true);
            r = invoke.invoke(r);
        }
    }

    private static boolean isThunk(Object r) {
        for (Class<?> c = r.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("dev.flix.runtime.Thunk$")) return true;
            for (Class<?> i : c.getInterfaces()) if (i.getName().equals("dev.flix.runtime.Thunk$")) return true;
        }
        return false;
    }
}
