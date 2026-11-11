package dev.flixw.spike;

/** The harness side of {@link Cov}: the arrays the generated code reads and writes. */
public final class CovState {
    private CovState() {}

    /** STATE[0]: active mutant (0 none); STATE[1]: record hits (1) or not (0); STATE[2]: number of recorded calls. */
    public static final int[] STATE = new int[3];
    /** HIT[n]: switch n was evaluated since the last {@link #clear}. */
    public static final boolean[] HIT = new boolean[1 << 16];

    /** Publishes the arrays; must run before the generated code loads {@link Cov}. */
    public static void publish() {
        System.getProperties().put("flixw.cov.state", STATE);
        System.getProperties().put("flixw.cov.hit", HIT);
    }

    public static void clear() { java.util.Arrays.fill(HIT, false); }
}
