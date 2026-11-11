package dev.flixw.mutate;

/** One mutant: switch number {@code id}, the site, and the operator it replaces the original with. */
public record Mutant(int id, Site site, String from, String to) {}
