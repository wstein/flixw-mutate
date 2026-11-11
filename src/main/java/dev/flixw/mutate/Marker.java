package dev.flixw.mutate;

/**
 * The switch and the coverage marker that the generated {@code FlixwMutant.on} calls. The class goes into a
 * small jar ({@link MarkerJar}) that is handed to the compiler: generated code finds Java classes outside the
 * JDK only through such a jar, and Flix loads them with its own class loader, so the worker's copy of this class
 * is a second one. The state therefore lives in two JDK arrays that the worker ({@link MarkerState})
 * publishes under {@code System.getProperties()}; JDK classes are shared between the loaders.
 */
public final class Marker {
    private Marker() { }

    /** STATE[0]: the active mutant (0: none); STATE[1]: not 0 while hits are recorded. */
    private static final int[] STATE = (int[]) System.getProperties().get(MarkerState.STATE_KEY);
    /** HIT[n]: switch n was evaluated since the last clear. */
    private static final boolean[] HIT = (boolean[]) System.getProperties().get(MarkerState.HIT_KEY);

    public static boolean on(int n) {
        if (STATE[1] != 0) HIT[n] = true;
        return n == STATE[0];
    }
}
