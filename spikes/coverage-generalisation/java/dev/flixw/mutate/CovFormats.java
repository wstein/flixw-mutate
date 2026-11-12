package dev.flixw.mutate;

import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Prototype writers for the three output formats: LCOV, Cobertura XML and a text report like flixw-mutate's. */
final class CovFormats {
    private CovFormats() {}

    /** Counter-probe: a partly hit line is written as not hit (must fail the format check of run.sh). */
    static final boolean DAP = "dap".equals(System.getProperty("cov.sabotage"));
    /** Sabotage: Cobertura branch lines write hits=0 for a partly hit line. */
    static final boolean DAPC = "dapc".equals(System.getProperty("cov.sabotage"));

    /** One LCOV record per file; {@code tn} is the test name (may be empty). {@code rel} maps a source name to its report path. */
    static String lcov(CovModel m, BitSet hit, String tn, Map<String, String> rel) {
        StringBuilder sb = new StringBuilder();
        for (String f : m.files()) {
            if (hit.isEmpty() && !tn.isEmpty()) continue;
            sb.append("TN:").append(tn).append('\n').append("SF:").append(rel.get(f)).append('\n');
            List<CovSites.Region> fns = m.of(f, CovSites.Kind.ENTRY);
            fns.sort(java.util.Comparator.comparingInt(CovSites.Region::line));
            int fnh = 0;
            for (CovSites.Region r : fns) sb.append("FN:").append(r.line()).append(',').append(r.name()).append('\n');
            for (CovSites.Region r : fns) {
                int h = hit.get(r.id()) ? 1 : 0;
                fnh += h;
                sb.append("FNDA:").append(h).append(',').append(r.name()).append('\n');
            }
            sb.append("FNF:").append(fns.size()).append("\nFNH:").append(fnh).append('\n');
            int brf = 0, brh = 0;
            for (CovSites.Region r : m.of(f, CovSites.Kind.THEN, CovSites.Kind.ELSE, CovSites.Kind.ARM)) {
                int h = hit.get(r.id()) ? 1 : 0;
                sb.append("BRDA:").append(r.line()).append(",0,").append(r.index()).append(',').append(h).append('\n');
                brf++;
                brh += h;
            }
            sb.append("BRF:").append(brf).append("\nBRH:").append(brh).append('\n');
            int lf = 0, lh = 0;
            for (var e : m.lines(f, hit).entrySet()) {
                int h = e.getValue() == 'M' || (DAP && e.getValue() == 'P') ? 0 : 1; // LCOV cannot say "partly"; BRDA carries the rest
                sb.append("DA:").append(e.getKey()).append(',').append(h).append('\n');
                lf++;
                lh += h;
            }
            sb.append("LF:").append(lf).append("\nLH:").append(lh).append("\nend_of_record\n");
        }
        return sb.toString();
    }

    static String cobertura(CovModel m, BitSet hit, Map<String, String> rel, String source) {
        int lv = 0, lc = 0, bv = 0, bc = 0;
        StringBuilder classes = new StringBuilder();
        for (String f : m.files()) {
            TreeMap<Integer, Character> lines = m.lines(f, hit);
            List<CovSites.Region> branches = m.of(f, CovSites.Kind.THEN, CovSites.Kind.ELSE, CovSites.Kind.ARM);
            int fl = lines.size(), fc = 0;
            for (char c : lines.values()) if (c != 'M') fc++;
            int fb = branches.size(), fbc = 0;
            for (CovSites.Region r : branches) if (hit.get(r.id())) fbc++;
            lv += fl; lc += fc; bv += fb; bc += fbc;
            classes.append(String.format(Locale.ROOT, "      <class name=\"%s\" filename=\"%s\" line-rate=\"%s\" branch-rate=\"%s\" complexity=\"0\">\n",
                esc(rel.get(f)), esc(rel.get(f)), rate(fc, fl), rate(fbc, fb)));
            classes.append("        <methods>\n");
            for (CovSites.Region r : m.of(f, CovSites.Kind.ENTRY)) {
                classes.append(String.format(Locale.ROOT, "          <method name=\"%s\" signature=\"\" line-rate=\"%s\" branch-rate=\"0\" complexity=\"0\">\n"
                    + "            <lines><line number=\"%d\" hits=\"%d\" branch=\"false\"/></lines>\n          </method>\n",
                    esc(r.name()), hit.get(r.id()) ? "1" : "0", r.line(), hit.get(r.id()) ? 1 : 0));
            }
            classes.append("        </methods>\n        <lines>\n");
            for (var e : lines.entrySet()) {
                int b = 0, bh = 0;
                for (CovSites.Region r : branches) if (r.line() == e.getKey()) { b++; if (hit.get(r.id())) bh++; }
                if (b == 0) {
                    classes.append(String.format("          <line number=\"%d\" hits=\"%d\" branch=\"false\"/>\n", e.getKey(), e.getValue() == 'M' ? 0 : 1));
                } else {
                    classes.append(String.format(Locale.ROOT, "          <line number=\"%d\" hits=\"%d\" branch=\"true\" condition-coverage=\"%d%% (%d/%d)\"/>\n",
                        e.getKey(), e.getValue() == 'M' || (DAPC && e.getValue() == 'P') ? 0 : 1, 100 * bh / b, bh, b));
                }
            }
            classes.append("        </lines>\n      </class>\n");
        }
        return "<?xml version=\"1.0\" ?>\n"
            + String.format(Locale.ROOT, "<coverage line-rate=\"%s\" branch-rate=\"%s\" lines-covered=\"%d\" lines-valid=\"%d\" branches-covered=\"%d\" branches-valid=\"%d\" complexity=\"0\" version=\"flixw-spike\" timestamp=\"0\">\n",
                rate(lc, lv), rate(bc, bv), lc, lv, bc, bv)
            + "  <sources><source>" + esc(source) + "</source></sources>\n  <packages>\n    <package name=\"flix\" line-rate=\"" + rate(lc, lv)
            + "\" branch-rate=\"" + rate(bc, bv) + "\" complexity=\"0\">\n      <classes>\n" + classes
            + "      </classes>\n    </package>\n  </packages>\n</coverage>\n";
    }

    /** Text report in the style of {@code Report.format}: one line per uncovered thing, then the summary and the attribution. */
    static String report(CovModel m, Map<String, String> rel, long baselineMs) {
        BitSet all = m.all();
        StringBuilder sb = new StringBuilder();
        int fnT = 0, fnH = 0, brT = 0, brH = 0, lnT = 0, lnH = 0, lnP = 0;
        for (String f : m.files()) {
            for (CovSites.Region r : m.of(f, CovSites.Kind.ENTRY)) {
                fnT++;
                if (all.get(r.id())) fnH++;
                else sb.append(String.format("%-12s%s:%d  %s\n", "not covered", rel.get(f), r.line(), r.name()));
            }
            for (CovSites.Region r : m.of(f, CovSites.Kind.THEN, CovSites.Kind.ELSE, CovSites.Kind.ARM)) {
                brT++;
                if (all.get(r.id())) brH++;
                else sb.append(String.format("%-12s%s:%d  %s %d\n", "not covered", rel.get(f), r.line(), r.kind().name().toLowerCase(Locale.ROOT), r.index()));
            }
            for (var e : m.lines(f, all).entrySet()) {
                lnT++;
                if (e.getValue() != 'M') lnH++;
                if (e.getValue() == 'P') lnP++;
            }
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "functions: %d (hit %d); branches: %d (hit %d); lines: %d (hit %d, of these partly %d)\n", fnT, fnH, brT, brH, lnT, lnH, lnP));
        sb.append(String.format(Locale.ROOT, "tests: %d (baseline %d ms)\n", m.perTest.size(), baselineMs));
        sb.append("tests per region: ");
        boolean first = true;
        for (var e : m.testsPerRegion().entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(e.getKey()).append(": ").append(e.getValue());
        }
        sb.append('\n');
        sb.append(String.format(Locale.ROOT, "line coverage: %s%% (hit or partly hit, of lines with code)\n", pct(lnH, lnT)));
        return sb.toString();
    }

    private static String pct(int a, int b) { return b == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", 100.0 * a / b); }

    private static String rate(int a, int b) { return b == 0 ? "1" : String.format(Locale.ROOT, "%.4f", (double) a / b); }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
