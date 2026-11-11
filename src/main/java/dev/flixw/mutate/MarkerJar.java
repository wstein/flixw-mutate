package dev.flixw.mutate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** The jar with {@link Marker} that the compiler gets for the schemata program. */
final class MarkerJar {
    static final String CLASS = Marker.class.getName();

    private MarkerJar() { }

    /** Writes a jar with the class file of {@link Marker}, found next to this class, to {@code target}. */
    static void write(Path target) throws IOException {
        String entry = CLASS.replace('.', '/') + ".class";
        try (InputStream in = Marker.class.getClassLoader().getResourceAsStream(entry);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            if (in == null) throw new IOException("class file not found: " + entry);
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
    }
}
