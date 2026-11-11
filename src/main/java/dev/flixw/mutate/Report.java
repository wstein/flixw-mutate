package dev.flixw.mutate;

import java.util.List;

/** The result of a run: one {@link Result} per mutant and the numbers around it. */
public record Report(List<Result> results, int skippedSites, int tests, long baselineMs, long timeoutMs) {
    public int count(Outcome outcome) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Percentage of mutants that were killed or timed out; -1 if there are no mutants. */
    public double score() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The text report: one line per mutant, then the summary. */
    public String format() {
        throw new UnsupportedOperationException("not implemented");
    }
}
