package dev.flixw.spike;

/** Overhead baseline: the same call as {@link CovMark#hit}, without recording. */
public final class CovNop {
    private CovNop() {}

    public static boolean hit(int n) {
        return false;
    }
}
