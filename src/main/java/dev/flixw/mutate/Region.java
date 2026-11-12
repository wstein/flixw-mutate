package dev.flixw.mutate;

import java.util.Objects;

/**
 * A region of the code that gets a coverage marker: where it is and what it stands for. {@code kind} is one of
 * {@link Point.Kind#FUNCTION} (the body of a def), {@code THEN}, {@code ELSE}, {@code ARM} and {@code LAMBDA}.
 * {@code line} is 1-based: the line of the def for a function, of the {@code if} or {@code match} for a branch, of
 * the body for a lambda. {@code file} is the name the compiler reports (an absolute path), {@code span} a half-open
 * range of char offsets in that file.
 */
public record Region(String file, Point.Kind kind, Span span, int line) {
    public Region {
        Objects.requireNonNull(file);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(span);
        switch (kind) {
            case FUNCTION, THEN, ELSE, ARM, LAMBDA -> { }
            default -> throw new IllegalArgumentException("not a region kind: " + kind);
        }
        if (line < 1) throw new IllegalArgumentException("line " + line);
    }
}
