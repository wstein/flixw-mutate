package dev.flixw.mutate;

import java.util.Objects;

/**
 * One mutation site: the whole expression and its operands (as spans in the original file). {@code right}
 * is null for a one-operand site ({@link SiteKind#NOT}).
 */
public record Site(String file, SiteKind kind, Span whole, Span left, Span right) {
    public Site {
        Objects.requireNonNull(file);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(whole);
        Objects.requireNonNull(left);
        boolean unary = kind == SiteKind.NOT;
        if (unary != (right == null)) throw new IllegalArgumentException(kind + " has " + (unary ? "one" : "two") + " operand(s)");
        if (!whole.contains(left) || (right != null && !whole.contains(right)))
            throw new IllegalArgumentException("operand outside the expression");
        if (right != null && left.end() > right.start()) throw new IllegalArgumentException("operands overlap");
    }
}
