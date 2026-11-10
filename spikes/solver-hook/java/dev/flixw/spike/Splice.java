package dev.flixw.spike;

import java.lang.reflect.Method;

/**
 * Route (a), run-time side. The rewritten {@code Fixpoint3.Solver.Def$runSolverInternal.staticApply}
 * calls {@link #before} with the constraint set and {@link #after} with the solver result. Both find the
 * compiled Flix hook module ({@code FlixwMutate.Hook}) through the class loader of the value they get,
 * because the generated classes exist only in the program's own loader. Compiled Flix functions return
 * a trampolined {@code dev.flix.runtime.Result$}, which {@link #force} runs to a value.
 */
public final class Splice {
    private Splice() {}

    public static Object before(Object datalog) throws Exception {
        Object r = call(datalog.getClass().getClassLoader(), "FlixwMutate.Hook.Def$flixwBefore", datalog);
        return value(r);
    }

    public static Object after(Object result) throws Exception {
        Object forced = force(result);
        Object model = value(forced);
        call(model.getClass().getClassLoader(), "FlixwMutate.Hook.Def$flixwAfter", model);
        return forced;
    }

    private static Object call(ClassLoader loader, String className, Object arg) throws Exception {
        Class<?> c = loader.loadClass(className);
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals("staticApply") && m.getParameterCount() == 1) return force(m.invoke(null, arg));
        }
        throw new NoSuchMethodException(className + ".staticApply");
    }

    /** Runs a trampolined result until it is no longer a thunk. */
    static Object force(Object r) throws Exception {
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

    /** The payload of a {@code dev.flix.runtime.Value$}. */
    static Object value(Object forced) throws Exception {
        return forced.getClass().getField("o").get(forced);
    }
}
