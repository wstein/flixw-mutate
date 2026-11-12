package dev.flixw.mutate;

import java.util.Objects;

/**
 * A Datalog rule or fact of the sources: where it is, the def around it and the key that a solve's rule or fact is
 * matched by. {@code kind} is {@link Point.Kind#RULE} or {@link Point.Kind#FACT}. For a rule the key is the
 * signature {@code Head<-Body1,Body2} (predicate names of the head and the body atoms, as the solver hook writes
 * it); for a fact it is the source text of the fact without white space and the final dot. {@code def} is the symbol
 * of the innermost def around the constraint, as the compiler prints it ({@code Module.name}), or empty.
 * {@code file} is the name the compiler reports (an absolute path).
 */
record DatalogSite(Point.Kind kind, String file, Span span, int line, String def, String key) {
    DatalogSite {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(file);
        Objects.requireNonNull(span);
        Objects.requireNonNull(def);
        Objects.requireNonNull(key);
        if (kind != Point.Kind.RULE && kind != Point.Kind.FACT) throw new IllegalArgumentException("not a Datalog kind: " + kind);
        if (line < 1) throw new IllegalArgumentException("line " + line);
    }

    /** The predicate a key starts with: the head of a rule signature, the name of a fact. */
    static String head(String key) {
        int i = 0;
        while (i < key.length() && key.charAt(i) != '<' && key.charAt(i) != '(') i++;
        return key.substring(0, i);
    }
}
