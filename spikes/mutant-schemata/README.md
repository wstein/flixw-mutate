# Spike: mutant schemata on Flix 0.79.0 (design option 4)

Part 2 of [flixw-mutate#1](https://github.com/wstein/flixw-mutate/issues/1), for [#9](https://github.com/wstein/flixw-mutate/issues/9).
Part 1 (solver hook) is in [`../solver-hook/`](../solver-hook/README.md).

## Question

Does option 4 run on the pinned compiler, Flix 0.79.0 (stock `flix.jar`)?

1. A site rewritten to `if (Mutant.on(n)) mutated else original` type-checks, including the `unsafe IO` wrapper for the Java state. What does the wrapper look like and which errors does the compiler give without it?
2. The `LoadedProgram` is loaded once; per mutant a switch is set and `program.tests` is rerun with a timeout.
3. Killed, survived, timed out and invalid mutants can be told apart.
4. How long does a mutant take, compared with one compile per mutant?

## Method

- `fixture/Calc.flix.tmpl`: ten sites written `@{id|original|mutated}@` in a small functional module; `fixture/TestCalc.flix` (9 tests); `fixture/Mutant.flix` (the switch). `mutants.tsv` holds the planted outcome per site (killed, survived, timeout, invalid); a survivor is marked in its note as equivalent or not.
- `java/dev/flixw/spike/SchemataRun.java` renders the template three ways: the original; the schemata program (every site as a switch expression); one program per mutant with only that site replaced (the baseline).
- The compiler is used through its public Scala API from Java, with nothing but the digest-checked `flix.jar` on the class path (`../common/java/.../FlixHost.java`): `new Flix(...).addFile(...)`, `compile()` returns `Result.Ok(CompilationResult)`, `JvmLoader.load(compilationResult)` returns the `LoadedProgram`, whose `tests` map holds `TestFn` values; `TestFn.run().apply()` runs one test. `Tester.run` was not used.
- A test counts as failed when it throws an exception whose class or message names an assertion (killed); any other exception is also reported as killed; a test that exceeds the timeout on its own daemon thread is a timeout; otherwise the mutant survived.
- The switch is the JVM system property `flixw.mutant`, read by `Mutant.on`.

## Commands

```sh
SPIKE_WORK=<scratch dir> sh spikes/mutant-schemata/run.sh          # RUNS=5 TIMEOUT_MS=3000 by default
```

It fetches `flix.jar` 0.79.0 (sha256 `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`, `../common/fetch-flix.sh`), compiles the Java sources with JDK 21 and runs the engine. Exit 1 if a planted outcome differs or if compile-per-mutant and schemata disagree.

Environment of the recorded run (2026-11-10): Linux 7.2 aarch64 (OrbStack VM), 10 CPUs, 8 GB RAM shared with other jobs (load average 18.8 before the recorded run; 7.4 before an earlier run of an earlier fixture version, see below), Temurin 21.0.13+9, `-Xmx2g`, Flix 0.79.0.

## Result: works, with the limits below

`sh spikes/mutant-schemata/run.sh`: exit 0, 10 of 10 sites as planted.

| Site | Planted | Actual | Note |
| --- | --- | --- | --- |
| 2, 8, 10 | survived, equivalent | survived | `x > hi` vs `x >= hi`, `x < 0` vs `x <= 0`, `x < lo` vs `not (x >= lo)`: same result for every Int32 |
| 1 | survived, not equivalent | survived | `x < lo` vs `x <= lo`: `clamp(10, 0, 10)` is 0 in the original and 10 in the mutant; no test calls `clamp` with `lo > hi` |
| 3, 4, 6, 7 | killed | killed | assertion failure |
| 5 | timeout | timeout | `count(n, ...)` instead of `count(n - 1, ...)`: the recursion never ends; timeout 3000 ms |
| 9 | invalid | invalid | mutated branch has type `Int32` where `String` is needed; the schemata compile fails |

The survivors 2, 8, 10 are equivalent by argument (not by a model comparison; there is none with schemata); site 1 is a real survivor that exposes a missing test.

- Original program: 9 of 9 tests pass. Schemata program with the switch off: 9 of 9 pass.
- Compile once, load once: setup (two compiles, see invalid mutants below, plus load) 4357 ms.
- Switch plus rerun of all 9 tests: 40 runs (8 mutants that did not time out, 5 runs each), mean 1.6 ms, median 0.8 ms, min 0.2 ms, max 7.9 ms.
- One compile per mutant (text replacement of the site, compile, load, run the tests): 8 runs, mean 1416 ms, min 1137 ms, max 1835 ms. Each outcome agrees with the schemata outcome.
- For these 8 mutants: setup 4.4 s plus about 0.06 s of switch runs (8 x 5 x about 1.6 ms) against 8 x 1.42 s = 11.3 s (arithmetic on the numbers above); break-even at about three mutants.
- These numbers come from one run at load average 18.8 (shared machine, not a benchmark); the times depend on the load: an earlier run of an earlier version of the fixture at load 7.4 gave switch 1.0 ms and compile-per-mutant 1457 ms, and the reviewer's run at load 15 gave 2.0 ms and about 5700 ms.

### The wrapper and the effect system

```flix
mod Mutant {
    import java.lang.System

    pub def on(n: Int32): Bool =
        Int32.fromString(unsafe IO { System.getProperty("flixw.mutant", "0") }) |> Option.getWithDefault(0) == n
}
```

Without `unsafe IO` (observed, compile error): `>> Unexpected effect 'IO' in {} function.` pointing at `pub def on(n: Int32): Bool = ... System.getProperty ...`. With `on` declared `\ IO`, a site in a pure function fails with the same message at the call in `pub def f(x: Int32): Int32 = if (on(1)) 0 else x` (`'IO' used here`). So every function that contains a site stays pure only because of the `unsafe IO` inside `Mutant.on`; no signature changes.

Observed (probes in the run output): when the switch is changed inside one test run, a later call of the pure-typed function does not follow it: `f(5)` before and after the change gave `(5, 5)` where `(5, 100)` was expected, and with another argument for the second call `f(6)` gave `(5, 6)`. A nullary def and a closure follow the switch when it changes between runs (`0, 1, 0, 1` gave `g=5, 100, 5, 100` and the same for the closure), so nothing is cached across runs. The cause inside a run is not isolated (UNKNOWN); a guess is that the optimizer moves the pure-typed reads around the IO effects of the test, but that was not checked. For the loop over mutants this is not a validity problem as long as the switch stays constant while a test runs; the risk is a task that keeps running after a timeout.

### Telling the outcomes apart

- killed: an assertion failure (`java.lang.AssertionError: Assertion failed: ...`). Any other exception is reported as killed as well here; the engine does not separate "killed by a crash".
- timeout: the call does not return within the timeout. `Thread.stop` does not exist in JDK 21, so the daemon thread is only interrupted. Site 5 is a real endless loop (`count(n, acc + 1)` with `n = 5`). Here the loop reads the switch on every iteration, so the engine resets the switch after the timeout and waits for the task to end before it measures anything else: in all 5 runs the runaway thread was gone after at most 7 ms and the engine fails the run if one is left. That works only for a loop that reads the switch; a loop that does not (an endless loop in code without a site, or a site reached only before the loop) cannot be stopped this way, and a worker JVM per run that can be killed is then needed (not built, not run). With `TIMEOUT_MS=60000 RUNS=1` site 5 is a timeout as well (`RESULT: OK`, one run, the runaway thread gone after 7 ms).
- survived: all tests pass. With schemata the engine cannot tell an equivalent mutant from a survivor (no model comparison as in part 1); sites 2, 8 and 10 are the planted equivalents and appear as survived, like site 1, which is a real survivor (not equivalent).
- invalid: a type error in a rewritten site breaks the single compile of the schemata program, not only that mutant. The error message names the site through a `/*S<id>*/` tag on its line; the engine removes the named sites and compiles again (here 2 compiles). The engine therefore pays one extra compile per round of invalid sites. Generating only well-typed mutants from the typed AST (as #9 plans) avoids it.

The expectations were checked against a deliberately wrong engine run: `JAVA_OPTS=-Dspike.sabotage=noswitch SPIKE_WORK=... sh spikes/mutant-schemata/run.sh` never sets the switch; exit 1, `RESULT: FAIL 10 mismatches`: sites 3, 4, 5, 6 and 7 differ from the plan (`actual=survived`) and compile-per-mutant disagrees with the schemata outcome for the same five sites; sites 1, 2, 8, 9 and 10 stay as planned.

## Limits

- One 10-site fixture; its compile is short, so the factor per mutant (in the recorded run 1416 ms against 1.6 ms, about 900; in the earlier run at load 7.4 1457 ms against 1.0 ms; in the reviewer's run at load 15 about 5700 ms against 2.0 ms) says little about real projects. The 10 to 100 times in the design is not checked here. `wstein/flix-invaders` was not used, as decided in #1.
- The schemata cost with the switch off (overhead of `Mutant.on` calls) was not measured: not run.
- Only expressions of type `Int32`, `Bool` and `String` and a `match` arm were used as sites. Sites are whole expressions (`if (...) (mutated) else (original)`); an operator inside a larger expression has to be wrapped as a whole expression, which the template does by hand. `Some`/`None`, `Ok`/`Err`, removed `do Op`, effect handlers, lattice operators: not run.
- `Tester.run` (output formatting, per-test regex filter) and `Bootstrap` projects (`flix.toml`, dependencies) were not used; files were added with `addFile`.
- The SDK has no `ProgramLoader` or `TestRunner` yet (`flix-bridge` on `feat/flix-bridge-adapters` has only the Metrics capability). The calls above are the ones such an adapter would wrap; the types involved are `ca.uwaterloo.flix.api.Flix`, `ca.uwaterloo.flix.runtime.{CompilationResult, JvmLoader, LoadedProgram, TestFn}`, `ca.uwaterloo.flix.util.Result`. Other Flix versions: not run.
- Mutation operators here are written by hand in the template; picking sites from the typed AST: not run.

## Proposal for ProgramLoader and TestRunner

Not part of this spike's scope beyond what the run shows; the shape that worked: `ProgramLoader.load(files) -> LoadedProgram` (compile once, load once, errors as a list of messages with source locations), `TestRunner.tests(program) -> Map<String, Test>` and `Test.run()` throwing on failure, with the timeout handled by the caller. Whether the plugin needs a worker process for timeouts is open.

## Recommendation

Option 4 works on 0.79.0 for the sites tried. Keep it in the plan. Before the engine is built: decide how a timed-out mutant is stopped (worker JVM), generate only well-typed sites, and measure on a real project.

## Open questions

- Is a forked worker JVM per run acceptable for loops that do not read the switch?
- What exactly makes a call after an in-run switch change keep the old value, and does it matter for sites in code called from handlers or lazily?
- Overhead of the switch calls on a real project with the switches off: not run.
