package dev.flixw.spike;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Compiles the given Flix files and prints the first line of every compile error, or "compiles". */
public final class CompileProbe {
    public static void main(String[] args) throws Exception {
        List<Path> files = new ArrayList<>();
        for (String a : args) files.add(Path.of(a));
        try {
            FlixHost.compile(FlixHost.newFlix(files));
            System.out.println("compiles");
        } catch (FlixHost.CompileError e) {
            e.getMessage().lines().filter(l -> l.startsWith(">>")).forEach(System.out::println);
        }
    }
}
