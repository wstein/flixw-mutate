package dev.flixw.mutate;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static dev.flixw.mutate.Check.*;

/** Selection of the compiler result constructor, including unsupported API forms. */
public final class CompilationResultConstructorTest {
    public static final class Three { public Three(Object a, Object b, Object c) {} }
    public static final class Four { public Four(Object a, Object b, Object c, Object d) {} }
    public static final class Two { public Two(Object a, Object b) {} }
    public static final class Multiple {
        public Multiple(Object a, Object b, Object c) {}
        public Multiple(Object a, Object b, Object c, Object d) {}
    }

    private static Constructor<?> select(Class<?> type) throws Exception {
        Method method = FlixApi.class.getDeclaredMethod("selectCompilationResultConstructor", Class.class);
        method.setAccessible(true);
        try {
            return (Constructor<?>) method.invoke(null, type);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw e;
        }
    }

    public static void main(String[] args) {
        Check c = new Check("CompilationResultConstructorTest");
        c.test("selects the three-parameter constructor", () -> eq(Three.class.getConstructors()[0], select(Three.class)));
        c.test("selects the four-parameter constructor", () -> eq(Four.class.getConstructors()[0], select(Four.class)));
        c.test("rejects an unknown parameter count", () -> throwsA(FlixApi.ApiError.class, () -> select(Two.class)));
        c.test("rejects ambiguous constructors", () -> throwsA(FlixApi.ApiError.class, () -> select(Multiple.class)));
        c.finish();
    }
}
