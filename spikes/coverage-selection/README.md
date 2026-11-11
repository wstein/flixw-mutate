# Spike: coverage-based test selection per mutant

For [#16](https://github.com/wstein/flixw-mutate/issues/16), part of [#9](https://github.com/wstein/flixw-mutate/issues/9). Built on the engine of PR #15 (`feat/engine-mutant-schemata-2`, head `97ac1c0273085b5767469524fafd59c3a448eb3b`, rebased from `9201d68`; the counts of the calc fixture below are from the rebased run, which added `CalcGuard`), which it does not change: `SiteFinder`, `Schemata` and `FlixApi` are compiled from `src/main` as they are. Spikes before it: [`../mutant-schemata/`](../mutant-schemata/README.md), [`../solver-hook/`](../solver-hook/README.md).

## Idea and method

A baseline run with every switch off records, per test, which site markers were evaluated. A mutant then runs only the tests that evaluated its site; a mutant that no test evaluated is `survived` without a run. A test that never evaluates the site behaves exactly as in the baseline, so for deterministic tests this loses no killed mutant. The spike checks that on two sets.

- Part A (schemata sites, functional code): `run-schemata.sh`, harness `java/dev/flixw/mutate/CoverageRun.java`. Sites and rendering are the engine's (`SiteFinder.find`, `Schemata.render`); the marker variants replace only the body of `FlixwMutant.on` (four variants, below). The program is compiled and loaded once per variant, the tests run one by one by name.
- Part B (Datalog): `run-datalog.sh`, `java/dev/flixw/spike/DatalogCoverage.java`. A Datalog mutant is not a schemata site (design option 6); it is a rewrite of the rule vector by the hook of `../solver-hook` (route b, unchanged). The "marker" is the hook's own record of the solves a test makes (rule count and body atoms per rule). A test reaches the mutant (op, rule r, atom a) if one of its solves has rule r (and atom a).
- Test objects: the engine's synthetic fixture `src/test/fixtures/calc` (11 tests, 24 sites, 34 mutants), a second synthetic fixture `fixture-wide/` (22 tests, 38 sites, 53 mutants: helpers many tests reach, a nullary def, a lazy value, a recursive loop, code no test calls, one hot loop), the Datalog fixture of `../solver-hook` and five Flix test files of v0.79.0 (fetched, not copied). `flix-invaders` was not used.
- Check per mutant: outcome kind and first killing test of the full run (all tests in name order, stop at the first failure, as `Worker.run`) equal those of the selected run. Second check: every unselected (mutant, test) pair is run alone under the mutant and must pass (a failure is a missed attribution).

## Commands

```sh
SPIKE_WORK=<scratch dir> sh spikes/coverage-selection/run-schemata.sh                 # calc and fixture-wide; TIMEOUT_MS=3000 REPS=7
SPIKE_WORK=<scratch dir> sh spikes/coverage-selection/run-datalog.sh material         # fixture and 5 Flix test files; MAX=40 per file
JAVA_OPTS=-Dspike.sabotage=nomarks SPIKE_WORK=... sh spikes/coverage-selection/run-schemata.sh <dir>   # markers never record: must fail
JAVA_OPTS=-Dspike.sabotage=nomarks SPIKE_WORK=... sh spikes/coverage-selection/run-datalog.sh          # no test reaches a mutant: must fail
```

Both scripts fetch the digest-checked `flix.jar` 0.79.0 (`../common/fetch-flix.sh`), compile with JDK 21 and exit 1 if an outcome differs. Environment of the recorded runs (2026-11-11): Linux 7.2 aarch64 (OrbStack VM), 10 CPUs, Temurin 21.0.13+9, Flix 0.79.0. The machine was shared and loaded: load average 15 to 30 at the start of the runs, compile+load of one program took 1 s to 29 s between runs. Times below are not benchmarks; counts (tests, mutants, outcomes) are exact and repeatable.

## Answers

### 1. Can the worker run and identify tests one by one?

Yes. `LoadedProgram.tests` is a map from the test symbol to a `TestFn`; `TestFn.run().apply()` runs one test, and the engine already does that by name in `Worker.run` (`FlixApi.tests` returns name, skip flag and a runnable per test). The harness ran every test alone: 11 (calc), 22 (fixture-wide), up to 30 (Flix files). A hit is attributed to a test by clearing the marker state before it and reading it after it; the test needs no cooperation.

Limits: tests run in name order on one thread of the worker JVM and share the JVM state, so "alone" means "alone in a warm JVM", not in a fresh one. That did not matter here: each selected run gave the same outcome as the full run, and each unselected test alone passed under the mutant (below). `@Skip` tests are not in the set. Tests that start threads: not run.

### 2. Cost of the marker, in run time and in code

Variants of `FlixwMutant.on(n)`, all switches off (`CoverageRun`, median of 7 runs after 2 warm-up runs, ms). The hot test calls `on` 8,000,993 times (1,000,000 loop iterations with 8 switch evaluations each, counted by the marker); suite = all 22 tests of fixture-wide. Two recorded runs, at load average about 15 (A) and about 26 (B):

| Variant of `on` | Suite A | Hot test A | Suite B | Hot test B |
| --- | --- | --- | --- | --- |
| original (no schemata) | 0.7 | 0.4 | 4.3 | 3.6 |
| `prop`: the engine's, parses `flixw.mutant` per call | 31.3 | 27.1 | 68.0 | 64.9 |
| `static`: `Cov.off(n)`, compare with a static, no marker | 1.4 | 0.7 | 4.4 | 1.9 |
| `rec`: `Cov.on(n)`, compare plus `HIT[n] = true` and a counter | 5.5 | 5.1 | 31.1 | 25.0 |
| `proprec`: JDK only, `System.setProperty("flixw.hit.<n>")` plus the property parse | 294.7 | 293.6 | 3246.4 | 3239.4 |

Per call (hot test minus original, divided by 8.0 million): `prop` 3.3 ns (A) and 7.7 ns (B); `rec` 0.6 ns and 2.7 ns; `proprec` 37 ns and 405 ns. A rerun on the rebased head (load 17 to 25) gave the same order for fixture-wide: original 2.0 / 1.0, `prop` 26.8 / 26.2, `static` 1.0 / 0.7, `rec` 4.8 / 4.3, `proprec` 251.0 / 250.4 (suite / hot test). The unmarked loop runs at 0.4 ns (A) to 3.6 ns (B) per iteration, so the numbers say how a loop of nothing but sites behaves, not a project. For calc (95 marker calls in the baseline) and the other 21 tests of fixture-wide the cost is below the noise of the runs (suite A 1.4 ms with `rec`, 0.7 ms without schemata). The marker with a static is cheaper than the engine's current property parse on each evaluation. The switch-off cost of the property parse that the earlier spike left as "not run" is the `prop` row against the `original` row.

In code: the module differs in one definition (`on`), plus a Java class `Cov` of about 15 lines. Generated code finds Java classes outside the JDK only through the `jars` argument of `new Flix(packages, jars, mounts)` and loads them with its own loader; `FlixApi.newFlix` passes `Nil` today, so the `rec` variant needs an engine change (a jar list). The `proprec` variant needs no jar but is 60 to 150 times slower per call than `rec`; it would only serve a separate coverage compile before the mutant program (one more compile and load, 1 s to 29 s in the runs here), which leaves the mutant program on the cheap `on`.

Class duplication (observed, a finding): with `Cov` on the application class path and in the jar, Flix loads its own copy (`ExternalJarLoader`; class identity of `Cov` differs from the harness's). The first run of the spike set the switch and read the hits on the harness's copy and saw nothing: all 53 mutants survived without a run, hit sets empty. The loader probe (a scratch program that prints the loader and identity hash of `Cov` from Flix code) is not in the repository. The state is therefore two JDK arrays (`int[]`, `boolean[]`) that the harness publishes under `System.getProperties()` (`CovState.publish`) and `Cov` reads at class initialisation; JDK classes are shared between the loaders. `Cov` is on the support jar only (`run-schemata.sh`).

### 3. Tests per site and the time that remains

Part A, tests per switch id (a mutant), exact:

| Fixture | Tests | Mutants | Tests per mutant (count of mutants) | Mean | Reached by none |
| --- | --- | --- | --- | --- | --- |
| calc | 11 | 34 | 0: 1, 1: 33 | 0.97 (8.8 %) | 1 |
| fixture-wide | 22 | 53 | 0: 4, 1: 43, 2: 4, 3: 1, 4: 1 | 1.09 (5.0 %) | 4 |

(Per mutant list: `$SPIKE_WORK/run-<fixture>/tests-per-mutant-<fixture>.tsv`.) The helper `Helpers.safeDiv`, which `Stats.mean` and `Shapes.ratio` call, has 4 tests, the most. The fixtures are written with one test per function; a project with broad integration tests would show larger numbers (not run).

Test executions over all mutants, full run against selected run (the full run stops at the first failing test, so a killed mutant is cheap in both): calc 245 against 33 (survivors only 110 against 9); fixture-wide 728 against 50 (survivors only 308 against 11).

Wall time over the mutants, without the timeout mutants (the timeout of 3000 ms per test dominates the rest; calc has 1, fixture-wide 2): calc 80 ms against 11 ms (load 17 to 25); fixture-wide 536 ms against 169 ms (load 26), 231 ms against 67 ms (load 17 to 25) and 197 ms against 44 ms (load 15). The tests here take 1 ms or less, so these times are mostly the hand-off to the test thread; they show the direction, not the factor.

Part B, Datalog (`run-datalog.sh material`, mutants capped at 40 per file by stride, here all of them: 30, 18, 17, 6, 9 and 15; every mutant of a file was run, plus one unreached probe). A test has a solve of its own in the "with solve" column:

| File | Tests | With solve | Mutants | Tests per mutant (count) | Mean | Executions full / selected |
| --- | --- | --- | --- | --- | --- | --- |
| `../solver-hook` fixture | 3 | 3 | 30 | 3: 30 | 3.00 (100 %) | 93 / 90 |
| Test.Exp.Fixpoint.If | 30 | 2 | 18 | 1: 10, 2: 8 | 1.44 (4.8 %) | 570 / 26 |
| Test.Exp.Fixpoint.PSolve | 11 | 11 | 17 | 3: 6, 8: 8, 11: 3 | 6.76 (61.5 %) | 198 / 115 |
| Test.Exp.Fixpoint.Solve.Simple | 23 | 4 | 6 | 1: 3, 2: 3 | 1.50 (6.5 %) | 161 / 9 |
| Test.Exp.Fixpoint.Solve.Project | 22 | 22 | 9 | 1: 3, 4: 3, 5: 3 | 3.33 (15.2 %) | 220 / 30 |
| Test.Exp.Fixpoint.Stratification | 9 | 9 | 15 | 1: 2, 4: 2, 5: 3, 8: 2, 9: 6 | 6.33 (70.4 %) | 144 / 95 |

The reach test is coarse (a solve with that many rules, not that rule's predicates), which makes the selection a superset; the fixture's three tests all call the same `analyse()`, so every test reaches every rule. Wall time over the mutants, full / selected: fixture 829 / 770 ms, If 5573 / 378, PSolve 844 / 366, Solve.Simple 1162 / 36, Solve.Project 1703 / 127, Stratification 728 / 300 (load about 23).

The 20 s per surviving mutant of the issue (465 tests on flix-invaders, author's figures) was not measured here. As arithmetic only: 20 s / 465 is about 43 ms per test; at the mean share of 5 % to 10 % of the tests of the two synthetic fixtures this is about 1 s to 2 s per survivor, at 60 % to 70 % (PSolve, Stratification) about 12 s to 14 s. The share depends on the project, not on the method.

### 4. Class load and sites several tests reach

Attribution stayed correct in what was run:

- A site that several tests reach is recorded for each of them (fixture-wide: 6 of 53 mutants have 2 to 4 tests, e.g. `Helpers.safeDiv` 4 tests, `Helpers.limit` 2 tests; calc has none, one test per function).
- Sites in code evaluated at class load: none. The marker state after compiling and loading a fresh copy of the program has 0 hits (the line "sites evaluated while compiling and loading" of the output), and the same program loaded fresh and run in reverse test order gives the same hit sets for every test (both fixtures: `true`). A site evaluated once at class initialisation or cached would show up for the first test only. Flix has no static initialiser for user code that this run saw; the generated classes were not inspected (UNKNOWN beyond the runs).
- A nullary def with a site (`Helpers.limit() = 10 + 5`) is evaluated again per call: both tests that call it record the site. A site inside a `lazy` expression is recorded by the test that forces it (`testLazy`).
- The variants `rec` (a static in Java) and `proprec` (system properties, JDK only) give the same hit sets for every test of both fixtures (`true`).
- Soundness check: all unselected (mutant, test) pairs run alone under the mutant: calc 341, fixture-wide 1108, none failed. Outcome and first killing test of full and selected run equal for all 34 + 53 schemata mutants and for all Datalog mutants (fixture 30, If 18, PSolve 17, Solve.Simple 7 including the probe, Solve.Project 9, Stratification 15).
- The check bites: with `-Dspike.sabotage=nomarks` (the marker never records), fixture-wide fails: 39 of 53 mutants differ and 45 unselected pairs fail under the mutant (the 14 survivors stay equal), `RESULT: FAIL 39 different, 45 missed`, exit 1; the Datalog run with no reach: `RESULT: FAIL 29 different`, exit 1.

Not covered by the runs: a mutant whose effect runs after the test that reached the site (a value in Java state that a later test reads), timeouts that hide a hit (a runaway test ends the run of the worker), tests that use threads, the optimiser moving or merging pure-typed `on` calls (the earlier spike saw `f(5)` give a stale value after a switch change inside one run; here the sets of `rec` and `proprec` agree and no pair failed, but that is not a proof).

### 5. A mutant that no test reaches

It can count as `survived` without a run, and the report has to say so (not `survived by the suite`, but `not covered`). Evidence: the mutant `==` inside the lazy operand of `CalcGuard.guarded` in calc (its right side is evaluated only for `n == 0`, which no test passes) and the four mutants of `Helpers.neverCalled` (no test calls it) are reached by none; their full run also gives `survived`, so outcomes agree (calc: 10 survivors, 1 of them without a run; fixture-wide: 14 survivors, 4 of them without a run). For Datalog every generated mutant comes from a solve of some test, so none is unreached; the probe (`drop-rule r99`, a rule no solve has) is reached by none, and its full run says `equivalent` (same model in every solve) where the selection says `survived`: the label differs because the full run compares models and the selection runs nothing (5 of 6 files; in Test.Exp.Fixpoint.Solve.Simple the unmutated probe says `survived`, which means that baseline and rerun models differ there; cause not isolated, UNKNOWN). A real engine would report `not covered` for such a mutant. That is a statement about this suite, not about the program: an equivalent mutant and an untested mutant both stay unkilled.

## Limits and what was not run

- One worker JVM per run; the tests run on a pool thread with a timeout (`FlixHost.runOne`), not on the worker thread as `Worker.run` does; a timed-out test is abandoned (`awaitAbandoned`), the switch reset ends the loops of the fixtures. A runaway test that does not read the switch needs the engine's worker kill.
- Not run: `wstein/flix-invaders` (the 890 mutants and 20 s of the issue; no copy was at hand and the issue names it as not the main object), Flix versions other than 0.79.0, tests that spawn threads, shared Java state between tests, Datalog source locations (the hook addresses rules by index in every solve), lattice and functional predicates, a project with `flix.toml` dependencies, the engine with markers built in (no engine change), a coverage compile separate from the mutant program (only discussed above), the cost of the marker under a heavier test body.
- The timing runs were done on a loaded shared machine; each number says which run it comes from.
- Two Datalog numbers come from the hook's coarse record: "reaches" ignores which predicates a rule has; a finer attribution needs the rule's text, which the hook already writes (`flixw.solve.N.rule.I`).

## Recommendation for the design decision

Selection from marker hits agrees with the full run on everything tested, and the marker with a static compare costs less than the engine's current property read. What it needs from the engine: a jar list for the Flix compiler call, one baseline run that reads the hit sets per test, `run <n> <tests>` in the worker protocol, and a report outcome `not covered`. The decision on that, and whether the Datalog route takes the coarse rule reach, is the owner's.
