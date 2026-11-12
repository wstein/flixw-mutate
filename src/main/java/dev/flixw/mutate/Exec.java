package dev.flixw.mutate;

/**
 * The start of one expression of a checked file: the places that make a line executable. A line that holds such
 * a start belongs to the innermost region around it.
 */
record Exec(String file, int offset, int line) {}
