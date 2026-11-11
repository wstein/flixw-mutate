package dev.flixw.mutate;

/**
 * What the tests said about one mutant. {@code NOT_COVERED}: no test evaluates the site, so the mutant
 * survives without a run.
 */
public enum Outcome {
    KILLED("killed"), SURVIVED("survived"), NOT_COVERED("not covered"), TIMEOUT("timeout");

    private final String label;

    Outcome(String label) { this.label = label; }

    /** The word the report uses. */
    public String label() { return label; }
}
