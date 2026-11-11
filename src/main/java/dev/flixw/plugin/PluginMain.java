package dev.flixw.plugin;

import dev.flixw.mutate.Engine;
import dev.flixw.mutate.Outcome;
import dev.flixw.mutate.Report;
import dev.flixw.sdk.kit.Context;
import dev.flixw.sdk.kit.ExitCodes;
import dev.flixw.sdk.kit.Usage;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

/** flixw plugin entry point; the shell (context, exit codes) comes from the SDK kit. */
public final class PluginMain {
    private static final String USAGE =
        "usage: ./flixw mutate [--timeout-ms <ms>] [--fail-on-survived] | --help | --version";

    private PluginMain() { }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    static int run(String[] args, Map<String, String> environment) {
        try {
            if (!"1".equals(environment.get("FLIXW_ABI_VERSION")))
                throw new Usage("requires flixw ABI version 1");
            if (args.length == 1 && "--help".equals(args[0])) {
                System.out.println(USAGE);
                return ExitCodes.SUCCESS;
            }
            if (args.length == 1 && "--version".equals(args[0])) {
                System.out.println(Header.line(Header.pluginVersion(), Header.sdkRevision(), null));
                return ExitCodes.SUCCESS;
            }
            Long timeoutMs = null;
            boolean failOnSurvived = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--timeout-ms" -> {
                        if (i + 1 == args.length) throw new Usage("--timeout-ms needs a value\n" + USAGE);
                        timeoutMs = positive(args[++i]);
                    }
                    case "--fail-on-survived" -> {
                        if (failOnSurvived) throw new Usage("unknown argument: " + args[i]);
                        failOnSurvived = true;
                    }
                    default -> throw new Usage("unknown argument: " + args[i]);
                }
            }
            // The compiler fields are absent when the project has no lock (flixw ABI 1).
            String jar = environment.get("FLIXW_COMPILER_JAR");
            if (jar == null || jar.isBlank())
                throw new Usage("missing FLIXW_COMPILER_JAR: the project has no lock");
            Context context = Context.read(environment);
            // flixVersion comes from the flixw ABI, not from lock.toml.
            String flix = environment.get("FLIXW_COMPILER_VERSION");
            if (flix == null || flix.isBlank())
                throw new Usage("missing FLIXW_COMPILER_VERSION");
            System.out.println(Header.line(Header.pluginVersion(), Header.sdkRevision(), flix));
            System.out.println("project root: " + context.projectRoot());
            String classpath = pluginLocation() + File.pathSeparator + context.compilerJar();
            Engine.Config config = new Engine.Config(context.projectRoot(), context.java(), classpath, timeoutMs,
                line -> System.err.println(line));
            Report report = Engine.run(config);
            System.out.print(report.format());
            return failOnSurvived && report.count(Outcome.SURVIVED) > 0 ? ExitCodes.POLICY_FINDING : ExitCodes.SUCCESS;
        } catch (Usage failure) {
            System.err.println("flixw-mutate: " + failure.getMessage());
            return ExitCodes.ERROR;
        } catch (Engine.Failure failure) {
            System.err.println("flixw-mutate: " + failure.getMessage());
            return ExitCodes.ERROR;
        }
    }

    private static long positive(String text) {
        try {
            long v = Long.parseLong(text);
            if (v > 0) return v;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new Usage("--timeout-ms needs a positive number of milliseconds, got: " + text);
    }

    /** The jar (or class directory) this plugin runs from: the worker JVM loads its classes from there. */
    private static String pluginLocation() {
        try {
            return Path.of(PluginMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new Usage("cannot locate the plugin jar: " + e.getMessage());
        }
    }
}
