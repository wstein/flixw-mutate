package dev.flixw.mutate;

import java.util.Objects;

/**
 * What a Datalog mutant does at the solver: {@code op} is {@code rule} (drop the rules with the signature
 * {@code key}), {@code fact} (drop the facts whose text is {@code key}) or {@code atom} (drop body atom number
 * {@code atom}, counted over the body atoms only, of the rules with the signature {@code key}). {@code row} is the id of
 * the rule or fact row of the site table that the mutant belongs to (its coverage selects the tests).
 */
record DatalogMutation(int row, String op, String key, int atom) {
    static final String RULE = "rule";
    static final String FACT = "fact";
    static final String ATOM = "atom";

    DatalogMutation {
        Objects.requireNonNull(key);
        if (!op.equals(RULE) && !op.equals(FACT) && !op.equals(ATOM)) throw new IllegalArgumentException("operator " + op);
        if (atom < 0) throw new IllegalArgumentException("atom " + atom);
    }
}
