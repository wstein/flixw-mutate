package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The order in which the selected tests of a mutant run. A run stops at the first failing test, so the expected
 * time to the first failure is smallest if the tests go by falling {@code p / t}: {@code t} is the duration of the
 * test in the baseline run, {@code p} the estimated chance that it fails under a mutant. The estimate is
 * {@code (kills + 1) / (runs + 2)} over the mutants of the same run that executed the test; nothing is stored
 * between runs. Durations under {@link #FLOOR_NANOS} count as the floor (below it they are timer noise, and the
 * chance decides). Equal keys keep the given order, so the first mutant of a run, and any run without durations, go in
 * name order. A mutant that one test fails and another hangs on is killed or timed out by whichever runs first, so the
 * order can turn TIMEOUT into KILLED; without a hanging test it changes only when and by which test a mutant is killed.
 */
final class TestOrder {
    /** Five milliseconds. */
    static final long FLOOR_NANOS = 5_000_000L;

    private final Map<String, Long> nanos;
    private final Map<String, int[]> history = new HashMap<>();

    /** @param nanos duration per test in the baseline run; a test missing from it counts as the floor */
    TestOrder(Map<String, Long> nanos) {
        this.nanos = Map.copyOf(nanos);
    }

    /** {@code tests} sorted by falling {@code p / t}; stable. */
    List<String> order(List<String> tests) {
        List<String> out = new ArrayList<>(tests);
        out.sort((a, b) -> Double.compare(key(b), key(a)));
        return out;
    }

    private double key(String test) {
        int[] h = history.getOrDefault(test, new int[2]);
        double p = (h[1] + 1.0) / (h[0] + 2.0);
        return p / Math.max(FLOOR_NANOS, nanos.getOrDefault(test, 0L));
    }

    /**
     * Records one mutant run of {@code sent}: the first {@code r.executed()} tests ran; if the mutant was killed, the last of
     * them failed. A timeout is not recorded (what ran is unknown); a mutant that no test ran is not either.
     */
    void record(List<String> sent, Result r) {
        if (r.outcome() == Outcome.TIMEOUT) return;
        for (int i = 0; i < r.executed() && i < sent.size(); i++) {
            int[] h = history.computeIfAbsent(sent.get(i), k -> new int[2]);
            h[0]++;
            if (r.outcome() == Outcome.KILLED && i == r.executed() - 1) h[1]++;
        }
    }
}
