# Spike: hook `Fixpoint3.Solver.runSolverInternal` without changing Flix

Part 1 of [flixw-mutate#1](https://github.com/wstein/flixw-mutate/issues/1) (design option 6). Part 2 (mutant schemata) is in [`../mutant-schemata/`](../mutant-schemata/README.md).

## Question

On the pinned compiler, Flix 0.79.0 (stock `flix.jar`, sha256 `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`): which route hooks `Fixpoint3.Solver.runSolverInternal` without changing Flix, (a) bytecode rewrite or (b) patched library source? Shown must be:

- a Datalog constraint set (`Constraint(HeadPredicate, Vector[BodyPredicate])`, as data) is changed before solving (drop a rule, drop a body atom, flip a negation) and the resulting model changes;
- invalid mutants (not stratifiable) are told apart from killed ones;
- a mutant with the same model is recognised as equivalent.

## Method

- `fixture/`: a Datalog fixture (`ReachGraph.flix`, reachability with a negation, 5 rules; `TestReachGraph.flix`, 3 asserting tests) and `mutants.tsv`: 11 planted mutants with the expected outcome (killed 6, equivalent 4, invalid 1) and the reason.
- `hook/Hook.flix`: the plugin's own Flix code. `flixwBefore` rewrites the rule vector of the solver input according to the switch (system properties `flixw.op/rule/atom`); `flixwAfter` records a canonical text of the resulting model. Both routes run this same code.
- Route (a), `java/.../RewriteSolver.java`: after `Flix.compile()`, the bytes of `Fixpoint3/Solver/Def$runSolverInternal` in `CompilationResult.root.classes` are rewritten with ASM (the copy inside `flix.jar`, `org.objectweb.asm`): `staticApply(ZLTagged$;)Ldev/flix/runtime/Result$;` is renamed `staticApply$orig` and a new `staticApply` calls `Splice.before`, the original, and `Splice.after`. `Splice` (Java) finds the compiled `FlixwMutate.Hook` classes through the program's class loader and runs the trampolined result. The rewritten `CompilationResult` is passed to `JvmLoader.load`.
- Route (b), `java/.../PatchSolver.java` and `Launcher.java`: `Fixpoint3/Solver.flix` is read from the jar, one anchor line (`def runSolverInternal(...) = region rc {`) is replaced by a wrapper that calls the hook around `runSolverInternal0`, and the hook body is appended inside `mod Fixpoint3.Solver`. The patched file lives in a directory that precedes `flix.jar` in a `URLClassLoader` (parent: platform loader); the compiler loads its library from that resource. Nothing in the jar changes.
- Engine, `java/.../SolverHookRun.java`: compile once, load once (`JvmLoader.load`), run all tests as baseline (hook active, no mutation), then per mutant set the switch, rerun the tests, classify. killed: a test assertion fails; invalid: the hook's own stratification check flags the mutant, or the solver or program throws with a `Fixpoint3.` frame; equivalent: no test fails and every recorded model equals the baseline model; survived: no test fails but a model differs.
- Real Flix material is fetched, not copied (`../common/fetch-flix-material.sh`, `../common/flix-material.sha256`): `examples/datalog/{graph-reachability,graph-closure,stratifier,taint-analysis,two-sat}.flix` and the 16 `main/test/flix/Test.Exp.Fixpoint.*.flix` of flix/flix at tag v0.79.0 (commit `36d923ca325dc466b28c63419667e6026a80f920`), each checked against its sha256. Nothing of it is in this repository. Mutants are generated automatically (every drop-rule, drop-atom and flip-negation that exists in some baseline solve, at most 40 per file by stride).

## Commands

```sh
SPIKE_WORK=<scratch dir> sh spikes/solver-hook/run.sh            # fixture, both routes, exit 1 on a deviation
SPIKE_WORK=<scratch dir> sh spikes/solver-hook/run.sh material   # additionally the fetched Flix material
```

Environment of the recorded runs (2026-11-10): Linux 7.2 aarch64 (OrbStack VM), 10 CPUs, 8 GB shared with other jobs (load average 8 to 45 during the work), Temurin 21.0.13+9, `-Xmx3g`, Flix 0.79.0.

## Result: both routes work on 0.79.0

Fixture, `sh spikes/solver-hook/run.sh`: exit 0; per route `RESULT: OK 11 mutants as expected` (counts `equivalent=4, invalid=1, killed=6`). Baseline: 3 of 3 tests pass with the hook active, the hook saw 3 solves.

| Mutant | Operation | Planted | Route (a) | Route (b) |
| --- | --- | --- | --- | --- |
| m01 | drop rule 0 | killed | killed | killed |
| m02 | drop rule 2 (`Unreach`) | killed | killed | killed |
| m03 | drop rule 3 (redundant) | equivalent | equivalent | equivalent |
| m04 | drop rule 1 (covered by rule 3) | equivalent | equivalent | equivalent |
| m05 | drop atom `Node(y)` of rule 3 | equivalent | equivalent | equivalent |
| m06 | flip `not Reach(n)` | killed | killed | killed |
| m07 | `Reach(x)` becomes `not Reach(x)` in rule 1 | invalid | invalid | invalid |
| m08 | drop atom `Reach(x)` of rule 4 | killed | killed | killed |
| m09 | drop atom `Reach(y)` of rule 4 | equivalent | equivalent | equivalent |
| m10 | drop rule 4 | killed | killed | killed |
| m11 | flip `Reach(y)` in rule 4 | killed | killed | killed |

Timings from the runs while developing (fixture, load average 8 to 45): route (a) compile 6.1 to 9.5 s, rewrite 2 to 4 ms, load 37 to 90 ms; route (b) compile 6.1 to 8.5 s, load 56 to 107 ms; per mutant (rerun of the 3 tests) 14 to 125 ms. Compile times follow the machine load; they are not a benchmark. Not measured: the time of a compile without the hook on a quiet machine.

The expectations were checked against deliberately wrong engine runs:

| Wrong run | Command (route b) | Exit | Deviation |
| --- | --- | --- | --- |
| switch never applied | `JAVA_OPTS=-Dspike.sabotage=nomutate SPIKE_WORK=... sh spikes/solver-hook/run.sh` | 1 | 7 of 11 differ (m01, m02, m06, m07, m08, m10, m11: `actual=equivalent`) |
| hook's stratification check off | `HOOK_ARGS=nocheck SPIKE_WORK=... sh spikes/solver-hook/run.sh` | 1 | 1 differs: m07 is `killed`, not `invalid` |

### Findings that shape the SolverHook contract

1. `runSolverInternal` is Flix library code that is compiled together with the user program. The compiled program contains the class `Fixpoint3/Solver/Def$runSolverInternal` with `public static final dev.flix.runtime.Result$ staticApply(boolean, Tagged$)` (seen with `javap`). A program that never calls the solver has no such class (route (a) reports `NO-TARGET`: `Test.Exp.Fixpoint.Constraint`, `Test.Exp.Fixpoint.Lambda`).
2. The library text is read from the jar resource `/src/library/Fixpoint3/Solver.flix` when the compiler starts (`ca.uwaterloo.flix.api.Library$` calls `LocalResource.get`); a resource of the same path that comes first in the compiler's class loader is used. Verified: route (b) compiles and runs with the patched file.
3. The standard library cannot see user sources, and a module must live in a file of its own name (compile errors `Undefined name`, `Mismatched module and file`). Route (b) therefore appends the hook inside `mod Fixpoint3.Solver`; `use` and `import` lines in the middle of a module are rejected, so the hook body uses fully qualified names and the patch adds one `import java.lang.System` and `import java.util.Objects` at the top.
4. The solver input is not always `Datalog(facts, rules)`: with `inject`ed facts it is `Join(Model, Datalog)`, and every `query` calls the solver again with `Join(Model, Datalog)` whose only rule has the head `$Result`. The hook recurses through `Join` and skips constraint sets with a `$Result` head. Before the solver numbers them, all `PredSym` ids are 0; predicates are told apart by name.
5. Fixpoint3 in 0.79.0 does not reject an unstratifiable program at run time: `Phase.Stratifier.stratify` adds an edge for every body atom and ignores the polarity, and with the hook's check off m07 ran to a wrong model that a test caught (`killed`, no exception). The compile-time stratifier of the Flix compiler only sees literal `#{...}` expressions. The design statement "the Stratifier rejects it at run time" does not hold for 0.79.0; the engine has to check stratification itself (the hook does: a negated atom on a dependency cycle).
6. Class loading: generated classes see Java classes only through the JDK and through the jars handed to `new Flix(packages, jars, mounts)`. Route (a) failed with `NoClassDefFoundError: dev/flixw/spike/Splice` until `Splice` was passed as a jar. The compiler also resolved `dev.flix.runtime.Global` only when `flix.jar` was on the application class path; with only an isolated loader it failed with `Undefined Java class 'dev.flix.runtime.Global'`. The launcher therefore keeps `flix.jar` on the application class path as well as in the isolated loader. How `flixw-metrics` avoids this: UNKNOWN (not looked at).
7. Dead-code removal: a hook function that no test or `main` reaches is dropped from the compiled program, and a call to a pure-typed `unsafe IO` function whose result is unused did not run (the hook counter stayed 0 until the results were used). `@DontInline` plus a used result kept the hook (route (a) uses a `@Test` that calls it; the runner skips it). The hook class is found by the exact name `FlixwMutate.Hook.Def$flixwBefore`; other specialisations of the same def carry a numeric suffix.
8. The erased representation of a Datalog value in the bytecode is `Tagged$` (field `ordinal`) with subclasses such as `Tag$Obj$Obj` (fields `v0`, `v1`) and singleton classes for nullary cases. Mutating it from Java by reflection is possible but ties the hook to this layout; doing it in Flix (both routes here) uses the enum constructors.

### Real Flix material

`sh spikes/solver-hook/run.sh material`, one JVM per file and route; the counts are identical in routes (a) and (b) in every row. Tests: baseline tests that pass with the hook active. Solves: solve calls of the program itself (queries excluded). Mutants: generated and run (cap 40 by stride, timeout 8 s).

| File (v0.79.0) | Tests | Solves | Mutants | Outcome counts |
| --- | --- | --- | --- | --- |
| Test.Exp.Fixpoint.If | 30 | 2 | 18 | killed 3, equivalent 3, invalid 12 |
| Test.Exp.Fixpoint.Large | 7 | 5 | 5 | invalid 4, error 1 |
| Test.Exp.Fixpoint.PQuery | 76 | 76 | 22 (of 43) | killed 1, invalid 7, error 14 |
| Test.Exp.Fixpoint.PSolve | 11 | 11 | 17 | killed 3, invalid 7, error 7 |
| Test.Exp.Fixpoint.Solve.Lattice | 25 | 11 | 10 | killed 2, invalid 8 |
| Test.Exp.Fixpoint.Solve.Project | 22 | 22 | 9 | killed 3, invalid 6 |
| Test.Exp.Fixpoint.Solve.Simple | 23 | 9 | 6 | killed 2, invalid 4 |
| Test.Exp.Fixpoint.Stratification | 9 | 9 | 15 | killed 3, invalid 12 |
| examples/datalog/stratifier | main only | 3 | 27 | survived 10, invalid 16, timeout 1 |
| examples/datalog/taint-analysis | main only | 4 | 13 | equivalent 2, invalid 8, error 3 |

No solve of the program's own, nothing mutated (the hook saw no constraint set): Test.Exp.Fixpoint.{Application, Compose, Functional, Inject, Permutations, Query} and examples graph-closure, graph-reachability, two-sat (a program that solves nothing but queries). The programs of graph-closure and graph-reachability call `query db, pr select ...` with their rules inside the query; the hook skips a constraint set with a `$Result` head, so rules written inside a query are not mutated (not checked in the solver input; this is the likely cause). Test.Exp.Fixpoint.Constraint and Test.Exp.Fixpoint.Lambda: the loaded program has 0 tests (why: UNKNOWN); route (a) reports `NO-TARGET`.

All 16 Fixpoint test files compiled together fail to compile (`The expression is not stratified. A predicate depends strongly on itself.`, 16 messages); each file compiles on its own, as Flix's own build does. The cause (predicate names shared across files) was not isolated.

Reading the counts: `invalid` includes mutants that make the solver or program crash with a `Fixpoint3.` frame, not only the stratification check; `error` is a non-assertion exception without such a frame (a test that panics on a changed model); both separations are heuristics on the stack. `survived`/`equivalent` here is by model comparison; the examples assert nothing, so only the model comparison speaks. A mutant is applied to every solve in the run that has the rule, not to one source location: the Datalog value carries no source position.

## Limits

- Flix 0.79.0 only. Other versions (0.76.1 to 0.78.0 are in the same adapter family): not run.
- Both routes need the hook logic in Flix and the Flix compiler API (`Flix`, `CompilationResult`, `JvmLoader`) through the stock jar; no change to Flix.
- Datalog with lattices (`Latticenal`), provenance (`runSolverWithProvenance`), functional predicates and guards as mutation targets: not run; guards and functionals are only counted (`?`).
- Mutants that loop forever: the timeout (8 s here) abandons a daemon thread; not stopped.
- Whether routes (a) and (b) survive a flixw plugin JVM (class loaders, `Flix` jars argument): not run.

## Vorschlag SolverHook (for the SDK; not implemented here)

Facts the contract needs, per Flix version, as data in `versions.json` and checked like other references (`AdapterAbi`, declared members, plus a smoke test):

| Item | 0.79.0 value |
| --- | --- |
| Hook target class (route a) | `Fixpoint3.Solver.Def$runSolverInternal` (JVM name `Fixpoint3/Solver/Def$runSolverInternal`), generated, not in the jar |
| Hook target method | `staticApply`, `public static final` |
| Descriptor | `(ZLTagged$;)Ldev/flix/runtime/Result$;` (`Z` is `withProv`, `Tagged$` the Datalog value) |
| Library source (route b) | resource `/src/library/Fixpoint3/Solver.flix`, anchor line `    def runSolverInternal(withProv: Bool, d: Datalog): Datalog = region rc {` exactly once |
| Types the hook reads | `Fixpoint3.Ast.Datalog.{Datalog, Constraint, BodyPredicate, HeadPredicate, Polarity}`, `Fixpoint3.Ast.Ram.RelSym`, `Fixpoint3.Ast.Shared.PredSym` |
| Compiler API | `Flix.compile`, `CompilationResult.root.classes` / `copy`, `JvmLoader.load`, `LoadedProgram.tests` |
| Smoke test | one rule dropped from a built-in program changes the model; baseline tests pass; hook counter greater than 0 |

Interface sketch: `SolverHook.open(compilerJar, hookSource, supportJar) -> Session` (compile once, load once), `Session.tests()`, `Session.run(switch, timeout) -> outcome` with the switch as data (`op`, `rule`, `atom`, optional solve ordinal) and the outcomes killed, survived, equivalent, invalid, timeout; `SolverHook.baseline()` returning the recorded models. The route is an implementation detail of the adapter per family, and the adapter reports `NO-TARGET` when the program does not use the solver.

## Recommendation

Both routes work. Prefer route (b) for the first adapter: the hook runs in Flix with typed constructors, there is no bytecode layout or support jar, and the per-version check is one anchor line plus the smoke test. Keep route (a) as the fallback when a version changes the library text but not the class: its check is one class, one method and one descriptor. The owner decision on the route is open (see below).

## Open questions

- Route (a) or (b) first? The numbers above show no difference in results; (b) needs the class loader trick and `flix.jar` on the system class path, (a) needs ASM from the jar and a support jar.
- How does a flixw plugin run with `flix.jar` visible to the compiler's Java lookup (finding 6)?
- The hook addresses rules by index in all solves; mapping a mutant to a source location needs the call site, which the Datalog value does not carry.
- Which of the twelve versions in the allowlist pass the smoke test: not run.
