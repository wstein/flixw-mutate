package dev.flixw.spike;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * Starts {@link SolverHookRun} in an isolated class loader that holds the stock flix.jar and, for route
 * (b), a directory with the patched {@code src/library/Fixpoint3/Solver.flix} in front of the jar; the
 * compiler jar itself is untouched. The launcher still needs flix.jar on the application class path (run.sh
 * does that): the compiler resolves Java classes such as dev.flix.runtime.Global through the system loader
 * (README, finding 6).
 *
 * Usage: Launcher a|b <flix.jar> <classes-dir> <work-dir> <fixture-dir> <mutants.tsv|auto> <hook.flix>
 *                 [nocheck|-] [support.jar]   (arguments 8 and 9; the support jar is needed by route a)
 */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        String route = args[0];
        Path jar = Path.of(args[1]);
        Path classes = Path.of(args[2]);
        Path work = Path.of(args[3]);
        List<URL> urls = new ArrayList<>();
        if (route.equals("b")) {
            String stock;
            try (ZipFile z = new ZipFile(jar.toFile());
                 InputStream in = z.getInputStream(z.getEntry(PatchSolver.RESOURCE.substring(1)))) {
                stock = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Path shadow = work.resolve("shadow");
            Path file = shadow.resolve(PatchSolver.RESOURCE.substring(1));
            Files.createDirectories(file.getParent());
            Files.writeString(file, PatchSolver.patch(stock, Files.readString(Path.of(args[6]))));
            urls.add(shadow.toUri().toURL() );
        }
        urls.add(classes.toUri().toURL());
        urls.add(jar.toUri().toURL());
        // Parent: platform loader only, so the application class path (this launcher) is not visible.
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> run = loader.loadClass("dev.flixw.spike.SolverHookRun");
            Thread.currentThread().setContextClassLoader(loader);
            try {
                run.getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            }
        }
    }
}
