package dev.flixw.mutate;

import java.util.List;

/**
 * What the baseline run recorded: the table of the mutants (one {@link Point.Kind#MUTANT} per switch number) and,
 * for each test, the switches whose site it evaluated (level {@link Hits#REACHED}). Test selection reads through
 * this table.
 */
public record Coverage(SiteTable sites, Hits hits) {
    /** The tests of the baseline run, in name order. */
    public List<String> tests() { return hits.tests(); }

    /** The tests that evaluated the site of mutant {@code id}, in name order. */
    public List<String> testsFor(int id) { return hits.testsFor(id); }
}
