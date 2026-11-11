package dev.flixw.mutate;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The part of the Flix compiler API the engine uses, reached by reflection so that the plugin builds without
 * the compiler on its class path and the calls sit in one place. The compiler jar is on the class path of the
 * worker JVM only. Written against Flix 0.79.0; the calls are the ones the mutant-schemata spike made
 * ({@code spikes/mutant-schemata}).
 */
final class FlixApi {
    /** A failed call into the compiler, or a compiler that does not have the expected API. */
    static final class ApiError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ApiError(String message, Throwable cause) { super(message, cause); }
    }

    /** The compile errors of a program, one text per message. */
    record Failure(List<String> messages) {}

    /** A checked program: its typed AST (an opaque object) or the errors. */
    record Checked(Object root, Failure failure) {}

    private final ClassLoader loader;

    FlixApi(ClassLoader loader) { this.loader = loader; }

    // --- compiler entry points -------------------------------------------------------------------------------

    private Object newFlix(List<Path> files) {
        try {
            Class<?> flixClass = cls("ca.uwaterloo.flix.api.Flix");
            Object packages = flixClass.getMethod("$lessinit$greater$default$1").invoke(null);
            Object jars = module("scala.collection.immutable.Nil$");
            Object mounts = flixClass.getMethod("$lessinit$greater$default$3").invoke(null);
            Object flix = flixClass.getConstructors()[0].newInstance(packages, jars, mounts);
            call(flix, "setFormatter", module("ca.uwaterloo.flix.util.Formatter$NoFormatter$"));
            Object unrestricted = module("ca.uwaterloo.flix.language.ast.shared.SecurityContext$Unrestricted$");
            for (Path f : files) call(flix, "addFile", f, unrestricted);
            return flix;
        } catch (ReflectiveOperationException e) {
            throw new ApiError("Flix API not as expected: " + e, e);
        }
    }

    /** Type-checks the files (the standard library is included by the compiler). */
    Checked check(List<Path> files) {
        Object flix = newFlix(files);
        Object result = call(flix, "check");
        Object root = optionGet(call(result, "_1"));
        List<String> messages = messages(call(result, "_2"));
        return new Checked(root, messages.isEmpty() ? null : new Failure(messages));
    }

    /** A compiled and loaded program. */
    record Program(Object loaded) {}

    /** Compiles and loads the files; the result is a {@link Program} or the errors. */
    Object compileAndLoad(List<Path> files) {
        Object flix = newFlix(files);
        Object result = call(flix, "compile");
        String kind = result.getClass().getSimpleName();
        if (kind.equals("Ok")) {
            Object loaded = call(module("ca.uwaterloo.flix.runtime.JvmLoader$"), "load", call(result, "t"));
            return new Program(loaded);
        }
        return new Failure(messages(call(result, "e")));
    }

    /** One test of a loaded program: its name, whether it is marked skip, and a way to run it. */
    record TestCase(String name, boolean skip, Runnable run) {}

    /** The tests of a program, sorted by name. */
    List<TestCase> tests(Program program) {
        Object map = call(program.loaded(), "tests");
        Map<?, ?> javaMap = (Map<?, ?>) callStatic("scala.jdk.javaapi.CollectionConverters", "asJava", "scala.collection.Map", map);
        TreeMap<String, TestCase> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : javaMap.entrySet()) {
            Object fn = e.getValue();
            String name = e.getKey().toString();
            boolean skip = (Boolean) call(fn, "skip");
            Object function0 = call(fn, "run");
            Method apply = viaMethod("scala.Function0", "apply", 0);
            sorted.put(name, new TestCase(name, skip, () -> {
                try {
                    apply.invoke(function0);
                } catch (InvocationTargetException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error er) throw er;
                    throw new IllegalStateException(cause);
                } catch (IllegalAccessException ex) {
                    throw new ApiError("cannot run a test: " + ex, ex);
                }
            }));
        }
        return new ArrayList<>(sorted.values());
    }

    // --- typed AST access ------------------------------------------------------------------------------------

    /** The class name of a typed-AST node, without the package ({@code TypedAst$Expr$ApplySig}). */
    static String nodeName(Object node) {
        String n = node.getClass().getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }

    /** The children of a node worth walking: the fields of typed-AST nodes, collections, options and tuples. */
    List<Object> children(Object node) {
        List<Object> out = new ArrayList<>();
        String cn = node.getClass().getName();
        if (isInstance(node, "scala.Option")) {
            if ((Boolean) via("scala.Option", node, "isDefined")) out.add(via("scala.Option", node, "get"));
        } else if (isInstance(node, "scala.collection.Iterable")) {
            Object it = via("scala.collection.Iterable", node, "iterator");
            while ((Boolean) via("scala.collection.Iterator", it, "hasNext")) out.add(via("scala.collection.Iterator", it, "next"));
        } else if (cn.startsWith("ca.uwaterloo.flix.language.ast.TypedAst$") || cn.startsWith("scala.Tuple")) {
            int arity = (Integer) via("scala.Product", node, "productArity");
            for (int i = 0; i < arity; i++) out.add(via("scala.Product", node, "productElement", i));
        }
        return out;
    }

    boolean isTypedAst(Object node) {
        return node != null && node.getClass().getName().startsWith("ca.uwaterloo.flix.language.ast.TypedAst$");
    }

    // --- reflection helpers ----------------------------------------------------------------------------------

    Class<?> cls(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private Object module(String className) {
        try {
            Field f = cls(className).getField("MODULE$");
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ApiError("Flix API not as expected: " + e, e);
        }
    }

    private boolean isInstance(Object o, String className) {
        try {
            return cls(className).isInstance(o);
        } catch (ClassNotFoundException e) {
            throw new ApiError("missing class " + className, e);
        }
    }

    private Object callStatic(String className, String name, String paramClass, Object arg) {
        try {
            Method m = cls(className).getMethod(name, cls(paramClass));
            return m.invoke(null, arg);
        } catch (ReflectiveOperationException e) {
            throw new ApiError("cannot call " + className + "." + name + ": " + e, e);
        }
    }

    private Object optionGet(Object option) {
        return (Boolean) via("scala.Option", option, "isDefined") ? via("scala.Option", option, "get") : null;
    }

    private List<String> messages(Object scalaList) {
        List<String> out = new ArrayList<>();
        Object formatter = module("ca.uwaterloo.flix.util.Formatter$NoFormatter$");
        Object none = call(module("scala.Option$"), "empty");
        Object it = via("scala.collection.Iterable", scalaList, "iterator");
        while ((Boolean) via("scala.collection.Iterator", it, "hasNext"))
            out.add(String.valueOf(call(via("scala.collection.Iterator", it, "next"), "message", formatter, none)).trim());
        return out;
    }

    /** Calls the method of the named public interface or class (for objects whose own class is not public). */
    Object via(String typeName, Object target, String name, Object... args) {
        try {
            return viaMethod(typeName, name, args.length).invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new ApiError(name + " failed: " + e.getCause(), e.getCause());
        } catch (IllegalAccessException e) {
            throw new ApiError("cannot call " + typeName + "." + name + ": " + e, e);
        }
    }

    private Method viaMethod(String typeName, String name, int arity) {
        try {
            return method(cls(typeName), name, arity);
        } catch (ReflectiveOperationException e) {
            throw new ApiError("cannot find " + typeName + "." + name + ": " + e, e);
        }
    }

    /** Calls the public method {@code name} with the arguments; the first method of that name and arity wins. */
    static Object call(Object target, String name, Object... args) {
        try {
            Method m = method(target.getClass(), name, args.length);
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new ApiError(name + " failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new ApiError("cannot call " + name + " on " + target.getClass().getName() + ": " + e, e);
        }
    }

    private static Method method(Class<?> type, String name, int arity) throws NoSuchMethodException {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity && !m.isBridge()) return m;
        }
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == arity) return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arity);
    }
}
