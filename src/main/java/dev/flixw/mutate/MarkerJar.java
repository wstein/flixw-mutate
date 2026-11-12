package dev.flixw.mutate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** The jar with {@link Marker} and {@link Splice} that the compiler gets for the schemata program. */
final class MarkerJar {
    static final String CLASS = Marker.class.getName();

    private MarkerJar() { }

    /** Writes a jar with the class files of {@link Marker} and {@link Splice}, found next to this class, to {@code target}. */
    static void write(Path target) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            for (Class<?> c : new Class<?>[] {Marker.class, Splice.class}) {
                String entry = c.getName().replace('.', '/') + ".class";
                try (InputStream in = c.getClassLoader().getResourceAsStream(entry)) {
                    if (in == null) throw new IOException("class file not found: " + entry);
                    out.putNextEntry(new JarEntry(entry));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
    }
}
