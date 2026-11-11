package dev.flixw.spike;

/**
 * Route (b) for the coverage hook: the anchor of {@link PatchSolver} (the same single line) is replaced by a
 * wrapper that calls {@code flixwCover(withProv, d, runSolverInternal0(withProv, d))}; the body of the hook
 * module is appended inside {@code mod Fixpoint3.Solver}.
 */
public final class CovPatch {
    private CovPatch() {}

    static final String REPLACEMENT =
            "    def runSolverInternal(withProv: Bool, d: Datalog): Datalog =\n"
            + "        flixwCover(withProv, d, runSolverInternal0(withProv, d))\n\n"
            + "    def runSolverInternal0(withProv: Bool, d: Datalog): Datalog = region rc {\n";
    static final String MODULE_OPEN = "mod FlixwCovHook {\n";
    static final String IMPORT = "    import java.lang.System\n";

    public static String patch(String stock, String hookModule) {
        int first = stock.indexOf(PatchSolver.ANCHOR);
        if (first < 0 || stock.indexOf(PatchSolver.ANCHOR, first + 1) >= 0) {
            throw new IllegalStateException("anchor of the Solver.flix patch not found exactly once");
        }
        int open = hookModule.indexOf(MODULE_OPEN);
        int end = hookModule.lastIndexOf('}');
        if (open < 0 || end < open) throw new IllegalStateException("hook module not as expected");
        String body = hookModule.substring(open + MODULE_OPEN.length(), end).replace(IMPORT, "");
        String patched = stock.replace(PatchSolver.ANCHOR, REPLACEMENT)
            .replace(PatchSolver.GLOBAL_IMPORT, PatchSolver.GLOBAL_IMPORT + IMPORT);
        int close = patched.lastIndexOf('}');
        return patched.substring(0, close) + "\n    // ---- flixw coverage hook (appended) ----\n" + body + patched.substring(close);
    }
}
