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
 * Route (b) launcher of the Datalog coverage prototype: the isolated class loader of the solver-hook spike's
 * {@code Launcher} (shadow directory with the patched Solver.flix in front of the stock jar), with {@link CovPatch}.
 *
 * Usage: GenLauncher <flix.jar> <classes-dir> <work-dir> <hook.flix|none> <main class> <args...>
 * ({@code none}: the stock library, for the run-time baseline)
 */
public final class GenLauncher {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]);
        Path classes = Path.of(args[1]);
        Path work = Path.of(args[2]);
        String stock;
        try (ZipFile z = new ZipFile(jar.toFile());
             InputStream in = z.getInputStream(z.getEntry(PatchSolver.RESOURCE.substring(1)))) {
            stock = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path shadow = work.resolve("shadow");
        Path file = shadow.resolve(PatchSolver.RESOURCE.substring(1));
        Files.createDirectories(file.getParent());
        Files.writeString(file, args[3].equals("none") ? stock : CovPatch.patch(stock, Files.readString(Path.of(args[3]))));
        List<URL> urls = new ArrayList<>();
        urls.add(shadow.toUri().toURL());
        urls.add(classes.toUri().toURL());
        urls.add(jar.toUri().toURL());
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> run = loader.loadClass(args[4]);
            Thread.currentThread().setContextClassLoader(loader);
            String[] rest = java.util.Arrays.copyOfRange(args, 5, args.length);
            try {
                run.getMethod("main", String[].class).invoke(null, (Object) rest);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            }
        }
    }
}
