package dev.flixw.mutate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/** What the baseline run recorded: for each test, the switch numbers (mutants) whose site it evaluated. */
public record Coverage(SortedMap<String, SortedSet<Integer>> hitsByTest) {
    public Coverage {
        SortedMap<String, SortedSet<Integer>> copy = new TreeMap<>();
        for (Map.Entry<String, SortedSet<Integer>> e : hitsByTest.entrySet()) copy.put(e.getKey(), new TreeSet<>(e.getValue()));
        hitsByTest = java.util.Collections.unmodifiableSortedMap(copy);
    }

    /** The tests of the baseline run, in name order. */
    public List<String> tests() { return new ArrayList<>(hitsByTest.keySet()); }

    /** The tests that evaluated the site of mutant {@code id}, in name order. */
    public List<String> testsFor(int id) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SortedSet<Integer>> e : hitsByTest.entrySet()) if (e.getValue().contains(id)) out.add(e.getKey());
        return out;
    }
}
