package dev.flixw.mutate;

import java.util.List;

/**
 * What the baseline run recorded: the site table (one {@link Point.Kind#MUTANT} row per switch number of a mutant;
 * with regions on also the region rows and the {@link Point.Kind#LINE} rows they own) and, for each test, the ids
 * whose site it evaluated (level {@link Hits#REACHED}). Test selection reads the MUTANT rows through this table.
 */
public record Coverage(SiteTable sites, Hits hits) {
    /** The tests of the baseline run, in name order. */
    public List<String> tests() { return hits.tests(); }

    /** The tests that evaluated the site of mutant {@code id}, in name order. */
    public List<String> testsFor(int id) { return hits.testsFor(id); }
}
