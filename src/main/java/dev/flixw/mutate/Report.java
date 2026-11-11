package dev.flixw.mutate;

import java.util.List;

/**
 * The result of a run: one {@link Result} per mutant and the numbers around it. {@code coverage} is null if it
 * was not recorded; {@code selection} tells whether a mutant ran only the tests that evaluate its site.
 */
public record Report(List<Result> results, int skippedSites, int tests, long baselineMs, long timeoutMs,
                     Coverage coverage, boolean selection) {
    public Report(List<Result> results, int skippedSites, int tests, long baselineMs, long timeoutMs) {
        this(results, skippedSites, tests, baselineMs, timeoutMs, null, false);
    }

    public int count(Outcome outcome) {
        int n = 0;
        for (Result r : results) if (r.outcome() == outcome) n++;
        return n;
    }

    /** Percentage of mutants that were killed or timed out; -1 if there are no mutants. */
    public double score() {
        if (results.isEmpty()) return -1;
        return 100.0 * (count(Outcome.KILLED) + count(Outcome.TIMEOUT)) / results.size();
    }

    /** The text report: one line per mutant, then the summary. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            sb.append(String.format("%-12s%s:%s  %s -> %s", r.outcome().label(),
                r.file(), r.position(), r.mutant().from(), r.mutant().to()));
            if (!r.detail().isEmpty()) sb.append("  ").append(r.detail());
            sb.append('\n');
        }
        if (!results.isEmpty()) sb.append('\n');
        sb.append("mutants: ").append(results.size()).append(" (killed ").append(count(Outcome.KILLED))
            .append(", survived ").append(count(Outcome.SURVIVED)).append(", not covered ").append(count(Outcome.NOT_COVERED))
            .append(", timeout ").append(count(Outcome.TIMEOUT))
            .append("); sites left out: ").append(skippedSites).append('\n');
        sb.append("tests: ").append(tests).append(" (baseline ").append(baselineMs).append(" ms, timeout per mutant ")
            .append(timeoutMs).append(" ms)\n");
        if (!results.isEmpty()) sb.append(selectionLines());
        sb.append("mutation score: ");
        if (results.isEmpty()) sb.append("n/a (no mutants)");
        else sb.append(String.format(java.util.Locale.ROOT, "%.1f%% (killed or timed out, of all mutants)", score()));
        sb.append('\n');
        return sb.toString();
    }

    /** The lines about test selection: tests per mutant and test executions. */
    private String selectionLines() {
        StringBuilder sb = new StringBuilder("test selection: ").append(selection ? "on" : "off");
        long executed = 0;
        long full = 0;
        for (Result r : results) {
            executed += r.executed();
            full += r.fullExecuted();
        }
        if (selection) {
            int[] counts = results.stream().mapToInt(Result::selected).sorted().toArray();
            java.util.TreeMap<Integer, Integer> histogram = new java.util.TreeMap<>();
            long sum = 0;
            for (int n : counts) {
                histogram.merge(n, 1, Integer::sum);
                sum += n;
            }
            sb.append(String.format(java.util.Locale.ROOT, "; tests per mutant: mean %.1f, median %d, max %d",
                (double) sum / counts.length, counts[(counts.length - 1) / 2], counts[counts.length - 1]));
            if (histogram.size() <= 8) {
                sb.append(" (");
                boolean first = true;
                for (var e : histogram.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(e.getKey()).append(": ").append(e.getValue());
                }
                sb.append(')');
            }
        }
        sb.append("\ntest executions: ").append(executed);
        if (selection) sb.append(" (a full run: ").append(full).append(", computed)");
        return sb.append('\n').toString();
    }
}
