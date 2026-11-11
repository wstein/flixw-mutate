package dev.flixw.mutate;

import java.util.List;
import java.util.Map;

/** Picks mutation sites from the typed AST of a checked program. */
final class SiteFinder {
    private SiteFinder() { }

    /** The sites per file (key as in {@code files}), rewrite order, and the number of candidates left out. */
    record Result(Map<String, List<Site>> sites, int skipped) {}

    static Result find(FlixApi api, Object root, Map<String, String> files) {
        throw new UnsupportedOperationException("not implemented");
    }

    static int offset(String text, int line, int col) {
        throw new UnsupportedOperationException("not implemented");
    }
}
