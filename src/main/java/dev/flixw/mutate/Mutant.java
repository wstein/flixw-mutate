package dev.flixw.mutate;

/**
 * One mutant: switch number {@code id}, the site, and the operator it replaces the original with. A Datalog mutant has
 * no site ({@code site} is null) and a {@code datalog} mutation; its id lies above the ids of the site table and is
 * no switch.
 */
public record Mutant(int id, Site site, String from, String to, DatalogMutation datalog) {
    public Mutant(int id, Site site, String from, String to) {
        this(id, site, from, to, null);
    }
}
