package dev.flixw.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The report header line. It names the three version levels separately: the plugin version
 * (manifest Implementation-Version), the SDK revision (the embedded sdk-source.properties pin)
 * and the Flix compiler version (FLIXW_COMPILER_VERSION from the flixw ABI).
 */
final class Header {
    static final String PIN = "/dev/flixw/plugin/sdk-source.properties";
    static final String UNAVAILABLE = "(unavailable)";

    private Header() { }

    static String line(String pluginVersion, String sdkRevision, String flixVersion) {
        return "flixw-mutate plugin=" + pluginVersion + " sdk=" + sdkRevision
            + (flixVersion == null ? "" : " flix=" + flixVersion);
    }

    static String pluginVersion() {
        String version = Header.class.getPackage().getImplementationVersion();
        return version == null ? UNAVAILABLE : version;
    }

    /** The pinned SDK commit that was embedded when this artifact was built. */
    static String sdkRevision() {
        try (InputStream in = Header.class.getResourceAsStream(PIN)) {
            if (in == null) return UNAVAILABLE;
            Properties pin = new Properties();
            pin.load(in);
            return pin.getProperty("revision", UNAVAILABLE);
        } catch (IOException e) {
            return UNAVAILABLE;
        }
    }
}
