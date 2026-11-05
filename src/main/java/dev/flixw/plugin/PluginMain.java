package dev.flixw.plugin;

import java.nio.file.Path;

/** Minimal flixw plugin entry point. */
public final class PluginMain {
    private PluginMain() { }

    public static void main(String[] args) {
        if (!"1".equals(System.getenv("FLIXW_ABI_VERSION"))) {
            System.err.println("This plugin requires flixw ABI version 1.");
            System.exit(2);
        }
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("usage: ./flixw plugin <plugin-name> [args...]");
            return;
        }
        if (args.length == 1 && "--version".equals(args[0])) {
            System.out.println("example flixw plugin 0.1.0");
            return;
        }

        Path root = Path.of(required("FLIXW_PROJECT_ROOT"));
        // For structured input, read the short-lived JSON file named by FLIXW_CONTEXT.
        // Its fields are additive: tolerate keys this template does not know yet.
        System.out.println("example plugin running in " + root);
        for (String arg : args) System.out.println(arg);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing " + name);
        return value;
    }
}
