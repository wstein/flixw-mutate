package dev.flixw.mutate;

import java.util.Objects;

/**
 * One row of the site table: a place in the source that a run can reach. {@code id} is the dense number of the
 * row in its {@link SiteTable} (0 only in a draft that the table numbers); {@code line} is 1-based; {@code span}
 * is a half-open range of char offsets in the file. This is not {@link Site}, which is a mutation site with its
 * operands; a mutant of a {@code Site} is one {@link Kind#MUTANT} row.
 */
public record Point(int id, Kind kind, String file, int line, Span span) {
    /**
     * What the row stands for. Functional regions (the spike of #22): {@code FUNCTION} the entry of a def,
     * {@code THEN}/{@code ELSE} the two branches of an {@code if}, {@code ARM} an arm of a {@code match},
     * {@code LAMBDA} a lambda body; {@code LINE} one part of a source line (owned by one region). Datalog:
     * {@code RULE} a rule of a constraint set, {@code FACT} a fact of it. {@code MUTANT} is the switch number of
     * the engine (the marker records which test evaluated the site of that mutant).
     */
    public enum Kind { MUTANT, FUNCTION, THEN, ELSE, ARM, LAMBDA, LINE, RULE, FACT }

    public Point {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(file);
        Objects.requireNonNull(span);
        if (id < 0) throw new IllegalArgumentException("negative id " + id);
        if (line < 1) throw new IllegalArgumentException("line " + line);
    }

    Point withId(int newId) { return new Point(newId, kind, file, line, span); }
}
