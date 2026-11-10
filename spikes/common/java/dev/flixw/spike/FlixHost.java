package dev.flixw.spike;

import ca.uwaterloo.flix.api.Flix;
import ca.uwaterloo.flix.language.CompilationMessage;
import ca.uwaterloo.flix.language.ast.shared.SecurityContext;
import ca.uwaterloo.flix.runtime.CompilationResult;
import ca.uwaterloo.flix.runtime.JvmLoader$;
import ca.uwaterloo.flix.runtime.LoadedProgram;
import ca.uwaterloo.flix.util.Formatter;
import ca.uwaterloo.flix.util.Result;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import scala.jdk.javaapi.CollectionConverters;

/**
 * The compiler API the spikes use, on the stock flix.jar and nothing else: compile files once, load the
 * program once ({@code JvmLoader.load}), run {@code LoadedProgram.tests} with a timeout.
 */
public final class FlixHost {
    private FlixHost() {}

    /** The compile error list of a failed {@link #compile}. */
    public static final class CompileError extends Exception {
        public CompileError(String message) { super(message); }
    }

    public static Flix newFlix(List<Path> files) { return newFlix(files, List.of()); }

    /**
     * {@code jars} are Java libraries the program may call. Generated code finds Java classes outside the JDK
     * only through these jars.
     */
    public static Flix newFlix(List<Path> files, List<Path> jars) {
        Flix flix = new Flix(Flix.$lessinit$greater$default$1(),
                CollectionConverters.asScala(jars).toList(), Flix.$lessinit$greater$default$3());
        flix.setFormatter(Formatter.NoFormatter$.MODULE$);
        for (Path f : files) flix.addFile(f, SecurityContext.Unrestricted$.MODULE$);
        return flix;
    }

    /** Compiles all added files (standard library included); the program stays in memory. */
    @SuppressWarnings("unchecked")
    public static CompilationResult compile(Flix flix) throws CompileError {
        Object r = flix.compile();
        if (r instanceof Result.Ok<?, ?> ok) return (CompilationResult) ok.t();
        Result.Err<?, ?> err = (Result.Err<?, ?>) r;
        StringBuilder sb = new StringBuilder();
        for (Object m : CollectionConverters.asJava((scala.collection.immutable.List<Object>) err.e())) {
            CompilationMessage cm = (CompilationMessage) m;
            sb.append(cm.message(Formatter.NoFormatter$.MODULE$, scala.Option.empty())).append('\n');
        }
        throw new CompileError(sb.toString().trim());
    }

    public static LoadedProgram load(CompilationResult cr) { return JvmLoader$.MODULE$.load(cr); }

    /** The tests of a loaded program by qualified name, sorted. {@code run} is {@code TestFn.run().apply()}. */
    public static TreeMap<String, Runnable> tests(LoadedProgram p) {
        TreeMap<String, Runnable> out = new TreeMap<>();
        CollectionConverters.asJava(p.tests()).forEach((sym, fn) -> out.put(sym.toString(), () -> fn.run().apply()));
        return out;
    }

    /** {@code LoadedProgram.main} as a pseudo test named "main", if the program has one. */
    public static Runnable main(LoadedProgram p) {
        if (p.main().isEmpty()) return null;
        return () -> p.main().get().apply(new String[0]);
    }

    public enum Outcome { PASS, FAIL, ERROR, TIMEOUT }

    /** Result of one test: FAIL is a failed Flix assertion, ERROR any other throwable. */
    public record TestResult(String name, Outcome outcome, String detail, Throwable error, long nanos) {}

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "flix-test");
        t.setDaemon(true);
        return t;
    });

    /**
     * Runs one test on a daemon thread. After the timeout the call returns TIMEOUT; the thread is
     * interrupted but, as Thread.stop is gone in JDK 21, a thread that does not poll for interruption
     * keeps running.
     */
    public static TestResult runOne(String name, Runnable fn, long timeoutMs) {
        long t0 = System.nanoTime();
        Future<?> f = POOL.submit(() -> { fn.run(); return null; });
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return new TestResult(name, Outcome.PASS, "", null, System.nanoTime() - t0);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new TestResult(name, Outcome.TIMEOUT, "> " + timeoutMs + " ms", null, System.nanoTime() - t0);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            Outcome o = isAssertion(c) ? Outcome.FAIL : Outcome.ERROR;
            return new TestResult(name, o, c.getClass().getName() + ": " + firstLine(c.getMessage()), c,
                    System.nanoTime() - t0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Runs the tests whose name does not start with one of the skipped prefixes. */
    public static List<TestResult> runAll(TreeMap<String, Runnable> tests, long timeoutMs, List<String> skipPrefixes) {
        List<TestResult> out = new ArrayList<>();
        tests.forEach((name, fn) -> {
            if (skipPrefixes.stream().noneMatch(name::startsWith)) out.add(runOne(name, fn, timeoutMs));
        });
        return out;
    }

    /** True if the throwable (or a cause) is the failure a Flix assertion raises. */
    static boolean isAssertion(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getClass().getName().contains("Assert") || String.valueOf(c.getMessage()).contains("Assertion")) {
                return true;
            }
        }
        return false;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** True if any frame of the throwable chain is in a class whose name starts with the prefix. */
    public static boolean stackHas(Throwable t, String classPrefix) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) if (e.getClassName().startsWith(classPrefix)) return true;
        }
        return false;
    }
}
