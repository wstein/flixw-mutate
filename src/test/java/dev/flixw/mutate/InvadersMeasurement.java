package dev.flixw.mutate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in real-project measurement using the same Session path as Engine.run. */
public final class InvadersMeasurement {
    private InvadersMeasurement() { }

    static List<String> sample(List<String> population, int count) {
        if (count < 1 || count > population.size()) throw new IllegalArgumentException("invalid sample size " + count);
        Map<String, List<String>> files = new TreeMap<>();
        for (String identity : population) {
            int tab = identity.indexOf('\t');
            if (tab < 0) throw new IllegalArgumentException("identity has no file");
            files.computeIfAbsent(identity.substring(0, tab), key -> new ArrayList<>()).add(identity);
        }
        files.values().forEach(list -> list.sort(String::compareTo));
        List<String> result = new ArrayList<>();
        for (int index = 0; result.size() < count; index++) {
            for (List<String> list : files.values()) {
                if (index < list.size()) result.add(list.get(index));
                if (result.size() == count) break;
            }
        }
        return List.copyOf(result);
    }

    static void requireSame(List<String> expected, List<String> actual) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("mutant identity or order changed");
    }

    static boolean sameOutcome(Outcome a, Outcome b, boolean fullComparison) {
        if (fullComparison) {
            if (a == Outcome.NOT_COVERED) a = Outcome.SURVIVED;
            if (b == Outcome.NOT_COVERED) b = Outcome.SURVIVED;
        }
        return a == b;
    }

    static String countKind(Outcome outcome) { return outcome == Outcome.TIMEOUT ? "upper-bound" : "measured"; }

    static List<String> differences(List<String> first, List<String> second, boolean fullComparison) {
        List<String> leftIds = new ArrayList<>();
        List<String> rightIds = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        if (first.isEmpty() || first.size() != second.size()) throw new IllegalArgumentException("missing results");
        for (String line : first) leftIds.add(resultIdentity(line));
        for (String line : second) rightIds.add(resultIdentity(line));
        requireSame(leftIds, rightIds);
        for (int i = 0; i < first.size(); i++) {
            Outcome a = Outcome.valueOf(first.get(i).split("\\t", -1)[6]);
            Outcome b = Outcome.valueOf(second.get(i).split("\\t", -1)[6]);
            if (!sameOutcome(a, b, fullComparison)) differences.add(leftIds.get(i) + "\t" + a + "\t" + b);
        }
        return differences;
    }

    private static String resultIdentity(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 12 || !fields[0].equals("result")) throw new IllegalArgumentException("bad result row");
        return String.join("\t", java.util.Arrays.copyOfRange(fields, 1, 6));
    }

    private static List<String> rows(Path path, List<String> identities) throws Exception {
        List<String> rows = Files.readAllLines(path).stream().filter(line -> line.startsWith("result\t")).toList();
        requireSame(identities, rows.stream().map(InvadersMeasurement::resultIdentity).toList());
        return rows;
    }

    private static String identity(Session s, Mutant m) {
        return s.file(m) + "\t" + s.position(m) + "\t" + m.id() + "\t" + m.from() + "\t" + m.to();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("compare")) {
            List<String> ids = Files.readAllLines(Path.of(args[1]));
            List<String> ordered = rows(Path.of(args[2]), ids);
            List<String> unordered = rows(Path.of(args[3]), ids);
            List<String> full = rows(Path.of(args[4]), ids);
            System.out.println("ordered_vs_unordered=" + differences(ordered, unordered, false));
            System.out.println("ordered_vs_full=" + differences(ordered, full, true));
            return;
        }
        if (args.length != 7) throw new IllegalArgumentException(
            "usage: InvadersMeasurement <probe|ordered|unordered|full> <project> <java> <worker-classpath> <timeout-ms> <sample-size> <manifest>");
        String mode = args[0];
        if (!List.of("probe", "ordered", "unordered", "full").contains(mode)) throw new IllegalArgumentException("bad mode");
        long timeout = Long.parseLong(args[4]);
        int count = Integer.parseInt(args[5]);
        if (timeout < 1) throw new IllegalArgumentException("timeout must be positive");
        Path manifest = Path.of(args[6]);
        Engine.Config config = new Engine.Config(Path.of(args[1]), Path.of(args[2]), args[3], timeout);
        if (mode.equals("unordered")) config = config.withoutOrder();
        if (mode.equals("full")) config = config.withoutSelection();
        long opened = System.nanoTime();
        try (Session s = Session.open(config)) {
            long openMs = (System.nanoTime() - opened) / 1_000_000;
            List<Mutant> covered = new ArrayList<>();
            int uncovered = 0;
            for (Mutant m : s.mutants()) {
                if (s.file(m).startsWith("src/Invaders/")) {
                    if (s.coverage().testsFor(m.id()).isEmpty()) uncovered++;
                    else covered.add(m);
                }
            }
            Map<String, Mutant> byIdentity = new TreeMap<>();
            for (Mutant m : covered) {
                if (byIdentity.put(identity(s, m), m) != null) throw new IllegalStateException("duplicate identity");
            }
            List<String> ids = sample(new ArrayList<>(byIdentity.keySet()), count);
            List<Mutant> chosen = ids.stream().map(byIdentity::get).toList();
            long maxBaselineMs = s.testNanos().values().stream().mapToLong(Long::longValue).max().orElse(0L) / 1_000_000;
            System.out.println("mode=" + mode + " tests=" + s.testCount() + " mutants=" + s.mutants().size()
                + " region_covered=" + covered.size() + " region_uncovered=" + uncovered + " sample=" + count
                + " timeout_ms=" + timeout + " open_ms=" + openMs + " baseline_ms=" + s.baselineMs()
                + " max_test_ms=" + maxBaselineMs);
            if (mode.equals("probe")) {
                Files.write(manifest, ids, java.nio.file.StandardOpenOption.CREATE_NEW);
                return;
            }
            requireSame(Files.readAllLines(manifest), ids);
            long started = System.nanoTime();
            for (Mutant m : chosen) {
                long before = System.nanoTime();
                Result result = s.run(m);
                long elapsed = (System.nanoTime() - before) / 1_000_000;
                System.out.println("result\t" + identity(s, m) + "\t" + result.outcome() + "\t" + result.selected()
                    + "\t" + result.executed() + "\t" + countKind(result.outcome()) + "\t" + elapsed
                    + "\t" + result.detail().replace("\t", " ").replace("\n", " ").replace("\r", " "));
            }
            System.out.println("execution_ms=" + (System.nanoTime() - started) / 1_000_000);
        }
    }
}
