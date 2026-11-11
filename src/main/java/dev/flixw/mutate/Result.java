package dev.flixw.mutate;

/**
 * The outcome of one mutant. {@code file} is relative to the project root, {@code position} is
 * {@code line:col} of the site in the original file, {@code detail} names the failing test for a killed
 * mutant and is empty otherwise.
 */
public record Result(Mutant mutant, String file, String position, Outcome outcome, String detail) {}
