package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The worker's side of {@link Marker}: the arrays the generated code reads and writes. */
final class MarkerState {
    static final String STATE_KEY = "flixw.mutate.state";
    static final String HIT_KEY = "flixw.mutate.hit";

    private MarkerState() { }

    /**
     * Publishes the arrays for switches 1 to {@code switches}; must run before the generated code loads
     * {@link Marker}. An array that is already published and long enough is kept (a loaded program holds it).
     */
    static void publish(int switches) {
        if (!(System.getProperties().get(STATE_KEY) instanceof int[])) System.getProperties().put(STATE_KEY, new int[2]);
        boolean[] hit = hits();
        if (hit == null || hit.length < switches + 1) System.getProperties().put(HIT_KEY, new boolean[switches + 1]);
    }

    private static int[] state() { return (int[]) System.getProperties().get(STATE_KEY); }

    private static boolean[] hits() {
        return System.getProperties().get(HIT_KEY) instanceof boolean[] h ? h : null;
    }

    /** Sets the active mutant (0: none) and whether hits are recorded. */
    static void set(int mutant, boolean record) {
        int[] s = state();
        s[0] = mutant;
        s[1] = record ? 1 : 0;
    }

    static void clear() { Arrays.fill(hits(), false); }

    /** The switches evaluated since the last {@link #clear}, ascending. */
    static List<Integer> hit() {
        boolean[] h = hits();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < h.length; i++) if (h[i]) out.add(i);
        return out;
    }
}
