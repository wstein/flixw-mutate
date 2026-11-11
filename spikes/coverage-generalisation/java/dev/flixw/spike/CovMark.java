package dev.flixw.spike;

/**
 * Marker target of the instrumented program (support jar; the state is two JDK arrays published by the harness,
 * as in the coverage-selection spike). {@code hit} records the region and returns false, so that the generated
 * code can use the result in an {@code if}: a call whose result is unused is removed by the Flix optimiser.
 */
public final class CovMark {
    private CovMark() {}

    private static final boolean[] HIT = (boolean[]) System.getProperties().get("flixw.gen.hit");

    public static boolean hit(int n) {
        HIT[n] = true;
        return false;
    }
}
