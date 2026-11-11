package dev.flixw.mutate;

/** A half-open range of char offsets in one source text. */
public record Span(int start, int end) {
    public Span {
        if (start < 0 || end < start) throw new IllegalArgumentException("bad span " + start + ".." + end);
    }

    public boolean contains(Span other) {
        return start <= other.start && other.end <= end;
    }
}
