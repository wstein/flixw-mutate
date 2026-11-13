package dev.flixw.mutate;

/**
 * The outcome of one mutant. {@code file} is relative to the project root, {@code position} is
 * {@code line:col} of the site in the original file, {@code detail} names the failing test for a killed
 * mutant and is empty otherwise. {@code selected} is the number of tests the mutant was run with,
 * {@code executed} the number of tests that ran (a run stops at the first failure; for a timeout it is not measured and
 * is the number of selected tests, an upper bound), {@code fullExecuted} the
 * number a run of all tests would execute: measured without selection; with selection computed as the position of
 * the killing test in name order, which is an upper bound (the selected tests run in another order, and a test
 * before the killer in name order may fail too); if such a test did not run it might hang as well, and the bound is all
 * tests, which is what a timeout counts; all tests for a mutant that survives (for a timeout, all tests).
 */
public record Result(Mutant mutant, String file, String position, Outcome outcome, String detail,
                     int selected, int executed, int fullExecuted) {
    public Result(Mutant mutant, String file, String position, Outcome outcome, String detail) {
        this(mutant, file, position, outcome, detail, 0, 0, 0);
    }
}
