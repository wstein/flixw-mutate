package dev.flixw.spike;

/**
 * Route (b): the patch applied to the standard library source {@code Fixpoint3/Solver.flix}. It is a text
 * transform with one anchor that must occur exactly once; any other compiler release that changes the
 * anchor makes the patch refuse to apply.
 */
public final class PatchSolver {
    private PatchSolver() {}

    public static final String RESOURCE = "/src/library/Fixpoint3/Solver.flix";
    public static final String ANCHOR = "    def runSolverInternal(withProv: Bool, d: Datalog): Datalog = region rc {\n";
    static final String REPLACEMENT =
            "    def runSolverInternal(withProv: Bool, d: Datalog): Datalog =\n"
            + "        flixwAfter(runSolverInternal0(withProv, flixwBefore(d)))\n\n"
            + "    def runSolverInternal0(withProv: Bool, d: Datalog): Datalog = region rc {\n";

    static final String MODULE_OPEN = "mod FlixwMutate.Hook {\n";
    static final String IMPORT = "    import java.lang.System\n    import java.util.Objects\n";
    static final String GLOBAL_IMPORT = "    import dev.flix.runtime.Global\n";
    static final String ROUTE_A_ONLY = "    // ---- route (a) only";

    /**
     * The library cannot see user sources (a separate package) and a module must match its file name, so the
     * body of the hook module is appended inside {@code mod Fixpoint3.Solver}, before its closing brace.
     */
    public static String patch(String stock, String hookModule) {
        int first = stock.indexOf(ANCHOR);
        if (first < 0 || stock.indexOf(ANCHOR, first + 1) >= 0) {
            throw new IllegalStateException("anchor of the Solver.flix patch not found exactly once");
        }
        int open = hookModule.indexOf(MODULE_OPEN);
        int cut = hookModule.indexOf(ROUTE_A_ONLY);
        if (open < 0 || cut < open) throw new IllegalStateException("hook module has no route (a) marker");
        String body = hookModule.substring(open + MODULE_OPEN.length(), cut).replace(IMPORT, "");
        String patched = stock.replace(ANCHOR, REPLACEMENT).replace(GLOBAL_IMPORT, GLOBAL_IMPORT + IMPORT);
        int close = patched.lastIndexOf('}');
        return patched.substring(0, close) + "\n    // ---- flixw-mutate hook (appended) ----\n" + body + patched.substring(close);
    }
}
