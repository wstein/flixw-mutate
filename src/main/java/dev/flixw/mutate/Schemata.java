package dev.flixw.mutate;

import java.util.List;

/** Renders mutant schemata: the sites of a file become calls into the switch module {@code FlixwMutant}. */
public final class Schemata {
    private Schemata() { }

    /** The rewritten file and the mutants its sites carry (ids from {@code firstId}, consecutive). */
    public record Rendered(String text, List<Mutant> mutants) {}

    /** The switch module {@code FlixwMutant} as Flix source. */
    public static String module() {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Rewrites {@code source}. Sites may nest only inside the operands of another site; anything else
     * (partial overlap, a site in the operator text) is an {@link IllegalArgumentException}.
     */
    public static Rendered render(String source, List<Site> sites, int firstId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** 1-based line and column of an offset, as {@code line:col}. */
    public static String position(String source, int offset) {
        throw new UnsupportedOperationException("not implemented");
    }
}
