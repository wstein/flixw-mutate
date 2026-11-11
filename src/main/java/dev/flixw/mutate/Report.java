package dev.flixw.mutate;

import java.util.List;

/** The result of a run: one {@link Result} per mutant and the numbers around it. */
public record Report(List<Result> results, int skippedSites, int tests, long baselineMs, long timeoutMs) {
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
            sb.append(String.format("%-10s%s:%s  %s -> %s", r.outcome().name().toLowerCase(java.util.Locale.ROOT),
                r.file(), r.position(), r.mutant().from(), r.mutant().to()));
            if (!r.detail().isEmpty()) sb.append("  ").append(r.detail());
            sb.append('\n');
        }
        if (!results.isEmpty()) sb.append('\n');
        sb.append("mutants: ").append(results.size()).append(" (killed ").append(count(Outcome.KILLED))
            .append(", survived ").append(count(Outcome.SURVIVED)).append(", timeout ").append(count(Outcome.TIMEOUT))
            .append("); sites left out: ").append(skippedSites).append('\n');
        sb.append("tests: ").append(tests).append(" (baseline ").append(baselineMs).append(" ms, timeout per mutant ")
            .append(timeoutMs).append(" ms)\n");
        sb.append("mutation score: ");
        if (results.isEmpty()) sb.append("n/a (no mutants)");
        else sb.append(String.format(java.util.Locale.ROOT, "%.1f%% (killed or timed out, of all mutants)", score()));
        sb.append('\n');
        return sb.toString();
    }
}
