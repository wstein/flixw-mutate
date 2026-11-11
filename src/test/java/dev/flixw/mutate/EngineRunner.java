package dev.flixw.mutate;

import java.nio.file.Path;

/** A process for the SIGTERM test: runs the engine on the project given as the only argument. */
public final class EngineRunner {
    public static void main(String[] args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Engine.run(new Engine.Config(Path.of(args[0]), java, System.getProperty("java.class.path"), null));
    }
}
