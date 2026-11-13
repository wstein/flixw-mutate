package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.List;

/** The worker's side of the solver hook: reads and clears the properties that {@link DatalogHook} writes. */
final class DatalogState {
    static final String PREFIX = "flixw.dl.";

    private DatalogState() { }

    /** What the solves of one test contained: rule signatures and fact texts, in solve order. */
    record Solves(int count, List<String> rules, List<String> facts) {}

    /** Forgets what the hook recorded. */
    static void clear() {
        System.getProperties().keySet().removeIf(k -> k.toString().startsWith(PREFIX));
    }

    /** What the hook recorded since the last {@link #clear}. */
    static Solves read() {
        int n = intProperty(PREFIX + "solves");
        List<String> rules = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int r = 0, nr = intProperty(PREFIX + i + ".rules"); r < nr; r++) rules.add(System.getProperty(PREFIX + i + ".rule." + r, ""));
            for (int f = 0, nf = intProperty(PREFIX + i + ".facts"); f < nf; f++) facts.add(System.getProperty(PREFIX + i + ".fact." + f, ""));
        }
        return new Solves(n, rules, facts);
    }

    /** The fingerprints (SHA-256, hex) of the models of the solves since the last {@link #clear}, in solve order. */
    static List<String> models() {
        List<String> out = new ArrayList<>();
        for (int i = 0, n = intProperty(PREFIX + "models"); i < n; i++) out.add(hash(System.getProperty(PREFIX + "model." + i, "")));
        return out;
    }

    private static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int intProperty(String key) { return Integer.parseInt(System.getProperty(key, "0")); }
}
