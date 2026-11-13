package dev.flixw.mutate;

/**
 * What the tests said about one mutant. {@code NOT_COVERED}: no test evaluates the site, so the mutant
 * survives without a run. {@code INVALID} (Datalog): the solver or the program threw on the changed constraint set, so it
 * is no mutant of the program (not counted as killed). {@code EQUIVALENT} (Datalog): the models of all solves equal those of
 * the original, so no test can see it; it is left out of the score.
 */
public enum Outcome {
    KILLED("killed"), SURVIVED("survived"), NOT_COVERED("not covered"), TIMEOUT("timeout"), INVALID("invalid"), EQUIVALENT("equivalent");

    private final String label;

    Outcome(String label) { this.label = label; }

    /** The word the report uses. */
    public String label() { return label; }
}
