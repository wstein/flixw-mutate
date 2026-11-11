package dev.flixw.spike;

/**
 * The marker target of the schemata sites in the coverage spike. Compiled into a support jar that is handed to
 * {@code new Flix(packages, jars, mounts)}: generated code finds Java classes outside the JDK only through
 * these jars, and Flix loads them with its own class loader. The harness cannot share this class with the
 * generated code (a second copy is loaded, see README), so the state is two JDK arrays that the harness
 * ({@link CovState}) publishes in the system properties under the keys below.
 */
public final class Cov {
    private Cov() {}

    private static final int[] STATE = (int[]) System.getProperties().get("flixw.cov.state");
    private static final boolean[] HIT = (boolean[]) System.getProperties().get("flixw.cov.hit");

    /** Switch without a marker. */
    public static boolean off(int n) { return n == STATE[0]; }

    /** Switch with a marker (records when STATE[1] is not 0 and counts the calls in STATE[2]). */
    public static boolean on(int n) {
        if (STATE[1] != 0) {
            HIT[n] = true;
            STATE[2]++;
        }
        return n == STATE[0];
    }
}
