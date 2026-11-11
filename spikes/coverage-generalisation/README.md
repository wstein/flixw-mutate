# Spike: marker coverage generalised to test coverage (functional Flix and Datalog)

For [#22](https://github.com/wstein/flixw-mutate/issues/22), refs [#18](https://github.com/wstein/flixw-mutate/issues/18). Stacked on PR #20 (`feat/engine-coverage-selection-2`, head `438e66d53be9be58b62d10dc5259b246ed79fff5`); #19 and #20 are not merged and not independently reviewed. The engine (`src/main`) is unchanged: `FlixApi` and `SiteFinder.offset` are compiled as they are. Spikes before it: [`../coverage-selection/`](../coverage-selection/README.md) (markers, selection), [`../solver-hook/`](../solver-hook/README.md) (Datalog hook, route b).

## Question

Can the marker approach of #18 be generalised to test coverage of functional Flix code and Datalog, and what shape does a core have that a mutation plugin and a coverage plugin share? Excluded by the owner: JaCoCo/bytecode coverage, a plugin, lattices. Flix 0.79.0 only.

## Result in short

- Functional: regions (function entry, `if` branch, `match` arm, lambda body) from the typed AST, one marker per region in a copy of the source, per-test hit sets from the baseline run. On a fixture with hand-written ground truth: 26 of 26 lines, 10 of 10 functions, 18 of 18 branches agree; three deliberately wrong variants each fail at the intended place. On flix-invaders (501 tests, 13.7k lines): all 501 tests pass instrumented as plain, the suite is 12 % slower in two clean rounds (5 % to 12 % by the minima). Known limits: lazy `and`/`or` operands and loop bodies are not regions (lines owned by the enclosing region read as hit).
- Markers survive the optimiser in the form `if (hit(n)) bug!(..) else { E }` (the result is used). In flix-invaders 1156 of 1256 markers are in the bytecode; 87 of the 100 missing are dead functions or inside one.
- Datalog: rules and facts per test through the solver hook are reachable at three levels: presence (cheap, exact), relation sizes (cheap), and tuples first derived per rule through a second solve with provenance (exact on the fixture, but it does not terminate on cyclic programs: 12 of 23 tests in `Test.Exp.Fixpoint.Solve.Simple` hit the 8 s timeout). The hook has no source positions; the rule is mapped to its source line through the typed AST by predicate names, unique for 419 of 432 rule instances once the search is narrowed to the test's def (in the 10 Flix test files that have a solve of their own).
- Formats: LCOV and Cobertura XML are generated and checked for record invariants and `coverage-04.dtd`; neither expresses a partly covered line; LCOV carries per-test records (`TN:`), 27.6 MB for 501 tests. The text report follows `Report.format`.
- Recommendation (section 4): one id space of sites, the existing marker class and `Coverage` table (test to site ids), two site sources and two hit sources behind the same table; keep the coverage compile separate from the mutant compile at first.

## Files

| Path | Content |
| --- | --- |
| `run.sh`, `java/dev/flixw/mutate/{CovSites,CovModel,CovFormats,GenRun}.java`, `java/dev/flixw/spike/{CovMark,CovNop}.java` | functional prototype: sites, rendering, harness, model, writers |
| `fixture/` | ground truth: line annotations `// H`, `// M`, `// P` in `src/*.flix`, `expected-functions.tsv`, `expected-branches.tsv`, `expected-limits.tsv` |
| `bench/` | hot loops for the marker overhead |
| `run-datalog.sh`, `datalog/CovHook.flix`, `java/dev/flixw/mutate/DatalogGen.java`, `java/dev/flixw/spike/{CovPatch,GenLauncher}.java` | Datalog prototype: hook (route b), launcher with the patched `Solver.flix`, harness |
| `datalog-fixture/` | Datalog ground truth: `expected-rules.tsv`, `expected-facts.tsv` |
| `check-formats.py` | LCOV record invariants and Cobertura against `coverage-04.dtd` (Python standard library; the DTD text is parsed, no validator is installed) |

## Commands

```sh
export SPIKE_WORK=<scratch dir>                                  # jars, classes, outputs
sh spikes/coverage-generalisation/run.sh                         # fixture with ground truth, exit 1 on a deviation
JAVA_OPTS="-Dcov.lambdas=false -Dcov.plain=false" sh spikes/coverage-generalisation/run.sh   # must fail: 1 line
JAVA_OPTS="-Dcov.sabotage=outer -Dcov.plain=false" sh spikes/coverage-generalisation/run.sh  # must fail: 8 lines
JAVA_OPTS="-Dcov.sabotage=nomarks -Dcov.plain=false" sh spikes/coverage-generalisation/run.sh # must fail: 41
python3 -I spikes/coverage-generalisation/check-formats.py $SPIKE_WORK/run-fixture <coverage-04.dtd>
sh spikes/coverage-generalisation/run.sh <project dir>           # any project with src/ and test/ (flix-invaders)
JAVA_OPTS="-Dcov.only=plain" REPS=15 sh .../run.sh spikes/coverage-generalisation/bench      # baseline, own JVM
JAVA_OPTS="-Dcov.target=nop -Dcov.plain=false" REPS=15 sh .../run.sh .../bench               # marker without recording
JAVA_OPTS="-Dcov.plain=false" REPS=15 sh .../run.sh .../bench                                # recording marker
sh spikes/coverage-generalisation/run-datalog.sh                 # Datalog fixture, exit 1 on a deviation
HOOK=<modified CovHook.flix> sh spikes/coverage-generalisation/run-datalog.sh               # sabotage runs
JAVA_OPTS=-Dflixw.covcfg.noprov=1 sh spikes/coverage-generalisation/run-datalog.sh material # no second solve
MATERIAL="If PSolve Solve.Project Stratification Solve.Simple" REPS=2 sh .../run-datalog.sh material
```

Both scripts fetch the digest-checked `flix.jar` 0.79.0 (`../common/fetch-flix.sh`), compile with JDK 21 and use the Flix compiler API. Datalog `material` fetches the Flix test files at the pinned revision (`../common/fetch-flix-material.sh`; not copied). flix-invaders: `wstein/flix-invaders` at `5df5ac1312b6216a474ab80bf7dd6556f335e238`, cloned read-only into the scratch directory. Environment of the recorded runs (2026-11-11): Linux 7.2 aarch64 (OrbStack VM), 10 CPUs, 8 GB shared with other jobs, Temurin 21.0.13+9, Flix 0.79.0; load average 6 to 60 during the work (the timing tables name their runs). Counts are exact and repeatable; times are not benchmarks.

## 1. Functional part

### 1.1 Method

`CovSites` walks the typed AST of the checked program (`FlixApi.check`) and records, for every `Def` in `src/`:

| Region | Node | Marker wraps | Line of the region |
| --- | --- | --- | --- |
| ENTRY | `Def` | the body | def line |
| THEN, ELSE | `IfThenElse` | `exp2`, `exp3` | line of the `if` |
| ARM | `Match` rule | the arm's `exp` | line of the `match` |
| LAMBDA | `Lambda` | the first body that is not a lambda | line of the body |

Every real, non-synthetic expression in the def is an expression start (file, offset, line). A line is owned by the innermost region that contains an expression start on it; the line is H if all its owners were hit, M if none, P if some. A partly hit line is a line where the code ran in part (`abs(n) = if (n < 0) -n else n` with a positive `n`).

`CovSites.render` writes a copy of each source file in which each region `E` is replaced by `(if (FlixwCov.hit(n)) bug!("flixw-cov") else { E })`; nothing is inserted across lines, so line numbers stay. `FlixwCov.hit(n)` is `unsafe IO { CovMark.hit(n) }`: it writes `HIT[n] = true` into a JDK array that the harness published (the class-loader reason is in the coverage-selection README) and returns `false`. The instrumented program is compiled once with the support jar, loaded once, and each test runs alone with the hit array cleared before and read after (`CovModel.perTest`: test to `BitSet` of region ids). Ids are numbered after sorting by file and offset, so two runs on the same sources give identical ids and outputs (checked: `cmp` of `regions.tsv`, `lcov.info`, `lcov-per-test.info`, `cobertura.xml` of two runs).

### 1.2 Accuracy against ground truth

`fixture/` has 3 source files (`Grade`, `Colors` with an instance, `Limits`) and 11 tests. The expected values of `Grade` and `Colors` were written from the code before the tool existed (commit `fcda057`; at that commit `run.sh` fails: no tool); `Limits` was added with the tool. Recorded run, `sh run.sh`: 32 regions (13 entry, 5 then, 5 else, 8 arm, 1 lambda), 97 expression starts, 11 tests pass instrumented and uninstrumented with equal outcomes.

| Check | Compared | Deviating |
| --- | --- | --- |
| lines (`// H`, `// M`, `// P`) | 26 | 0 |
| functions (`expected-functions.tsv`) | 10 | 0 |
| branches (`expected-branches.tsv`) | 18 | 0 |
| known limits (`expected-limits.tsv`, truth differs from the tool by listing) | 2 | 0 |

Counter-probes (each run must fail at the intended cause; recorded runs):

| Variant | Command (`JAVA_OPTS`) | Result |
| --- | --- | --- |
| no lambda regions | `-Dcov.lambdas=false` | `Grade.flix:25` expected P, got H (1 deviation): the lambda in `total(Nil)` never runs, its line reads as hit |
| a line is owned by the outermost region | `-Dcov.sabotage=outer` | 8 line deviations (every line inside an unhit branch reads H); functions and branches unaffected |
| markers never record | `-Dcov.sabotage=nomarks` | 41 deviations: 20 lines, 9 functions, 12 branches |
| a Cobertura writer without `complexity` on `<method>` | (earlier version) | `check-formats.py`: 10 violations of `coverage-04.dtd`, 0 after the fix |

Known limits (`fixture/src/Limits.flix`): `a and check(x)` with `a = false` and a `foreach` over `Nil`. The truth is P for both lines; the tool says H, because the right operand and the loop body are not regions. Both are listed in `expected-limits.tsv` with truth and tool value, and `run.sh` fails if the tool changes either way.

### 1.3 Marker survival after optimisation

Flix 0.79.0 has no switch for its optimiser (`Options` has no such field), so every run is optimised. Forms tried on a one-function program (compile only, no test run): a marker statement `{ hit(n); E }` is rejected (`Non-unit statement`, `Useless expression: It is pure and its result is discarded`); `let h = hit(n); E` is rejected (`Unused local variable`). `let _ = hit(n); E`, `match hit(n) { case _ => E }` and a wrapper `after(n, E)` produced no error in the same compile, but the program was not run: whether they survive is UNKNOWN. The form `if (hit(n)) bug!(..) else { E }` uses the result and has the marker before `E` (a panic in `E` still counts as reached); tail calls stay tail calls (`bench`: `loop` with 10,000,000 iterations runs instrumented without stack overflow).

Audit of the bytecode (`GenRun.audit`: ASM scan of `CompilationResult.root.classes` for `invokestatic CovMark.hit` with the preceding int constant):

| Program | Inserted | Distinct ids in bytecode | Call sites | Ids at more than one call site | Missing |
| --- | --- | --- | --- | --- | --- |
| fixture | 32 | 31 | 31 | 0 | 1: the entry of `Grade.unused`, which no test calls |
| bench | 14 | 14 | 14 | 0 | 0 |
| flix-invaders | 1256 | 1156 | 1825 | 28 (at most 308 sites for one id) | 100: ENTRY 24, LAMBDA 33, ARM 17, ELSE 13, THEN 13; 87 are a missing entry or inside one |

A missing marker can only read "not hit". The 87 are explained by functions the compiler dropped; the other 13 are branches or lambdas removed inside live functions (cause not isolated: UNKNOWN). An id at several call sites is a copy made by inlining or specialisation; a boolean hit set is not affected, a count per call site would be. That markers are not moved to another place is shown only by the ground truth above; there is no proof.

### 1.4 Overhead

Marker cost, `bench` (3 tests: a loop of 10,000,000 iterations with 3 regions per iteration, a loop of 5,000,000 iterations with a 3-arm `match` (4 regions), a fold over a list of 1,000,000 with a lambda). Minimum of 15 runs per test, each variant in its own JVM; load average 12 to 23. Plain: no instrumentation; nop: the instrumented program with a marker that does not record (`CovNop`); recording: `CovMark`. Three rounds; the figures are the range of the minima (ms):

| Test | Plain | Nop | Recording | Recording minus plain |
| --- | --- | --- | --- | --- |
| loop, 30,000,000 marker calls | 5.08 to 5.16 | 5.08 to 5.17 | 6.23 to 6.25 | +1.1 ms (21 %), 0.04 ns per call |
| loopMatch, 20,000,000 marker calls | 2.93 to 2.98 | 2.92 to 2.98 | 3.21 to 3.23 | +0.3 ms (10 %), 0.014 ns per call |
| sumTo, 1,000,000 lambda calls | 5.54 to 6.25 | 5.43 to 5.62 | 5.30 to 5.63 | not distinguishable |

The medians vary much more than the minima (round 1 loop plain median 8.6 ms, round 3 5.1 ms): the machine was shared. The JIT inlines the static call; the loops run at 0.5 ns per iteration, so this says how a loop of nothing but markers behaves, not a project.

flix-invaders, whole suite of 501 tests, instrumented against plain, each in its own JVM, 3 runs per round (suite median / min, ms): round 1: plain 6540 / 6342, instrumented 7306 / 7088; round 2: plain 6202 / 6126, instrumented 6975 / 6407; round 3 (machine busy, plain 12970 / 10417, instrumented 8251 / 6502, not usable). Rounds 1 and 2: +12 % and +12 % (medians), +12 % and +5 % (minima). Compile of 13.7k lines: plain 2.7 s, instrumented 3.9 s; extra front-end pass for the sites (`check` 4.8 s, site walk 2.2 s; the engine already checks once before it compiles). Compile and check times follow the machine load (the same fixture took 1.4 s to 11 s to compile in different runs).

### 1.5 flix-invaders

`sh run.sh <clone>` at `5df5ac1`: 1256 regions (517 entry, 163 then, 168 else, 202 arm, 206 lambda) and 10,680 expression starts; 76 more candidate regions were left out (see 1.6). 501 tests, all pass instrumented and plain with equal outcomes (`java.awt.headless=true`). Result: functions 517 (438 hit), branches 533 (406 hit), lines 2297 with code (1784 hit, of these 8 partly hit; 77.7 %). Tests per region (for selection in #16): 256 of 1256 regions are reached by no test, 71 by one test; of the 1000 regions that are hit, the mean is 60.2 tests (12.0 % of 501), the median 24. Expression nodes seen inside defs, for the share of constructs without a region: `IfThenElse` 183, `Match` 152, `TryCatch` 6, `RunWith` 7, `Handler` 7, `Region` 4, `LocalDef` 4, `Binary` (`and`/`or`) 39, the rest are straight-line expressions. There is no ground truth for flix-invaders: the line figures are not checked beyond the equal test outcomes and the marker audit (UNKNOWN: accuracy on this program).

### 1.6 Typed-AST pitfalls found on the way

Each was found by a compile error on flix-invaders after the fixture was green; each guard is in `CovSites`.

- A record literal's location starts after its opening brace; wrapping it gave `Malformed <tuple>`. Regions whose span text has unbalanced brackets are left out (`balanced`).
- A destructuring `let` is desugared to a `match` whose arm span starts at the bound expression; wrapping it gave `Useless expression`. Branch regions are made only for `if` and `match` nodes whose source text starts with that keyword.
- An `if`/`else` wrapper is not a closed expression after `|>`: `lines |> if (..) .. else { f } |> g` mis-parses. The wrapper has outer parentheses.
- `(a; b)` is not an expression and `{ -n }` reads as a record restriction: the wrapper uses a block, with an inner parenthesis when the text starts with `-`.
- A multi-parameter lambda is desugared to nested lambdas with a synthetic location: the region is made at the first body that is not a lambda.
- `root.instances` is a `ListMap`, which `FlixApi.children` does not descend into: instance defs (`instance ToString[Color]`) were not seen until `CovSites` visited `ListMap.m`. `SiteFinder` walks with `FlixApi.children`, so by reading it skips mutation sites in instance methods too (not run).
- Instance defs carry a numeric suffix (`toString$57116`) that changes between runs; the prototype replaces it by `@line`.
- Default implementations of trait methods are `Sig` nodes, not `Def`: they get no entry region (not measured).

## 2. Datalog part

### 2.1 Method

`datalog/CovHook.flix` is hook route (b) of `../solver-hook`, with one hook function: `runSolverInternal` calls `flixwCover(withProv, d, runSolverInternal0(withProv, d))` (`CovPatch`: the same anchor line as `PatchSolver`, replaced once; the body of the module is appended inside `mod Fixpoint3.Solver`). For every solve of the program the hook writes JVM properties: the rule and fact texts, a signature `Head<-Body1,Body2` per rule (predicate names of atoms), and, from a second solve with provenance (`runSolverInternal0(true, d)`), the model tuples whose first derivation is each rule (rule index `-1`: tuples of facts) and the size of each relation. `DatalogGen` runs every test alone, reads the properties, and maps each rule to its source line through the typed AST (`TypedAst$Constraint` has `loc`, head and body predicates), by signature, narrowed to constraint sets inside the test's own def.

Findings about the hook input (0.79.0):

- `solve p` then `query m select ..` gives two solves: the program's, and one with only `$Result` rules (skipped). `query p select ..` directly gives one solve with the program's rules plus one `$Result` rule, which is recorded (the mutation hook of `../solver-hook` skips every solve with a `$Result` rule and so misses the rules of this form: with that variant `DlNet.evens` has 0 solves and 4 deviations).
- A `Boxed` literal prints as `Tag$Int32@<hash>` with `Debug.stringify` and with the constraint's own `ToString`; its `ToString[Boxed]` instance gives the value. The hook prints heads and bodies itself.
- The first hook ended in `if (cond) unreachable(m) else m` with `unreachable(m) = m` (both branches equal) and recorded 0 solves; that run also had the `$Result` skip above, so the cause is not separated. The hook now uses `bug!(..)` in the first branch, the device of finding 7 in `../solver-hook` (a result that is used and a branch that differs).
- A rule is not tied to a source position in the solver input.

### 2.2 Ground truth

`datalog-fixture/` (`DlNet.flix`, `TestNet.flix`): `reachable()` with 5 facts and 4 rules (one never fires: `Dead(x) :- Reach(x), Edge(x, 99)`), solved then queried; `evens()` with 3 facts, 1 rule with a guard, queried directly; `double()` without Datalog. Expected tuples first derived per rule, fact counts and rule lines are in the TSV files (derived: 1, 2, 0, 1 and 2, 2 for the `$Result` rule; facts 5 and 3). `sh run-datalog.sh`: 9 comparisons, 0 deviating; 2 of 3 tests have a solve; 6 rule signatures, 5 fired in some test, 1 never; rule line unique for 6 of 6. Counter-probes (modified hook through `HOOK=`):

| Variant | Result |
| --- | --- |
| index off by one (`let i = rule`) | 5 deviations (derived counts shift to the wrong rule) |
| skip every solve with a `$Result` rule | 4 deviations (rules and facts of `evens`, one test without a solve more) |
| `-Dflixw.covcfg.noprov=1` (no second solve) | 6 deviations: derived is n/a; presence and relation sizes remain |

### 2.3 Granularity reachable, stability, effort

| Level | Source | Cost | Exact | Result |
| --- | --- | --- | --- | --- |
| rule and fact present in a solve of the test | constraint vectors at the hook | one property write per rule | yes | 10 of the 12 material files (the other two have no solve of their own) |
| head relation size | model at the hook | one pass over the model | per predicate: rules that share a head predicate (`Reach` has two) are not told apart | works without provenance |
| tuples first derived per rule | second solve with provenance | a second solve | on the fixture yes | cyclic programs do not terminate |
| fact used (tuple level) | proof trees (`ProvenanceReconstruct.provQuery`) or an own body evaluation | not built | not run | UNKNOWN |

Real material (Flix 0.79.0 test files, `run-datalog.sh material`, `TIMEOUT_MS=8000`, hook against stock library, suite median of REPS, shared machine):

| File | Tests / with a solve / solves | Rule signatures | Source line unique: signature alone / narrowed to the test's def | No provenance: hook / stock ms | Provenance: fired, never fired, n/a / hook / stock ms |
| --- | --- | --- | --- | --- | --- |
| Test.Exp.Fixpoint.If | 30 / 30 / 31 | 21 | 9 / 67 of 67 | 229 / 172 | 16, 0, 5 (lattice) / 383 / 334 |
| Test.Exp.Fixpoint.PSolve | 11 / 1 / 2 | 4 | 0 / 6 of 8 | 33 / 38 | 3, 1, 0 / 104 / 84 |
| Test.Exp.Fixpoint.Solve.Simple | 23 / 21 / 24 | 13 | 8 / 61 of 72 | 40 / 41 | 13, 0, 0 / 96061 / 88; 12 of 23 tests time out |
| Test.Exp.Fixpoint.Solve.Project | 22 / 5 / 5 | 4 | 2 / 10 of 10 | 39 / 46 | 4, 0, 0 / 108 / 66 |
| Test.Exp.Fixpoint.Stratification | 9 / 9 / 9 | 11 | 8 / 23 of 23 | 55 / 26 | 11, 0, 0 / 75 / 90 |
| Test.Exp.Fixpoint.Query | 19 / 13 / 13 | 18 | 51 / 57 of 57 | 33 / 50 | not run |
| Test.Exp.Fixpoint.Functional | 19 / 19 / 19 | 26 | 18 / 43 of 43 | 85 / 47 | not run |
| Test.Exp.Fixpoint.Permutations | 48 / 48 / 48 | 7 | 0 / 96 of 96 | 160 / 140 | not run |
| Test.Exp.Fixpoint.Application | 27 / 27 / 27 | 12 | 6 / 54 of 54 | 80 / 84 | not run |
| Test.Exp.Fixpoint.Compose | 4 / 1 / 1 | 2 | 1 / 2 of 2 | 3 / 3 | not run |
| Test.Exp.Fixpoint.PQuery, Inject | 76 / 0 / 0 and 27 / 0 / 0 | 0 | none | 106 / 155 and 31 / 30 | not run |

Reading the table: the unit of "unique" is a rule instance (a rule in one solve of one test); over all rows 419 of 432 instances get one source line after narrowing (by signature alone 103 of 432). The tests of PQuery and Inject have no solve of their own: they inject facts and query a model, so the hook sees only `$Result` solves (the same files that the mutation hook could not mutate). Hook cost without provenance is inside the noise of the shared machine; with provenance the suite is 0.8 to 1.6 times the stock time in the runs here (REPS=2) except Solve.Simple, where the provenance solve does not terminate for the symmetric, cyclic `Edge`/`Path` programs (12 tests, 8 s each). In the first material run, which had no lattice check, three tests of `If` that use a lattice predicate failed with an error in the second solve; the hook now skips the provenance solve when a constraint has a lattice denotation (`fwHasLattice`). Lattice files (`Solve.Lattice`) were not run.

Stability across versions: only 0.79.0 was run. The hook depends on the anchor line of `runSolverInternal` in `Fixpoint3/Solver.flix` (as the mutation hook), the names `Datalog`, `Constraint`, `HeadPredicate.HeadAtom(PredSym, Denotation, terms)`, `BodyPredicate.BodyAtom(PredSym, Denotation, Polarity, Fixity, terms)`, `HeadTerm`, `BodyTerm`, `Boxed` with `ToString`, `Denotation.Latticenal` (4 fields), `RelSym.Symbol(PredSym, _, _)`, the `Provenance(rules, model)` value with `(depth, ruleUsed)` and `ruleUsed == -1` for facts, and `runSolverInternal0`. These are library internals, not API; any change of the Fixpoint3 library between releases can break the patch or the pattern matches, and other versions are UNKNOWN. Per-version data for a contract (anchor, types, smoke test) is the list above plus the smoke test of this fixture.

Effort in this spike: hook 139 lines of Flix, harness 321 lines of Java, launcher and patch 83 lines. A tuple-level fact use through an own body evaluation over the final model is estimated at 100 to 150 more lines of Flix against `HeadTerm`/`BodyTerm`/`Boxed` (estimate, not built; guards and functionals would need the function values of the constraint).

## 3. Output formats

All three are generated from the same model by `CovFormats` (functional part) and `DatalogGen.lcov` (Datalog part).

| Property | LCOV (`.info`) | Cobertura XML | Own report (`report.txt`) |
| --- | --- | --- | --- |
| Line hit | `DA:line,count` | `<line number hits>` | yes |
| Partly hit line | not expressible: `DA` is 1; `BRDA` carries the branches | `condition-coverage="50% (1/2)"` on branch lines | yes (`partly`) |
| Function | `FN`, `FNDA` | `<method>` with its line | yes (`not covered` lines) |
| Branch | `BRDA:line,block,branch,taken` | only the condition-coverage string; `<conditions>` is optional and not written | yes |
| Per test | `TN:<test>` records; per-test file is 27.6 MB for 501 tests (lines only) | not in the format | tests per region histogram, `Coverage` table |
| Counts | counts in `DA`/`FNDA`; the marker is a boolean: count is 1 | `hits` | not needed |
| Datalog rule | `FN:line,signature`, `FNDA:<tuples derived>`, `DA` of the rule line | `<method>` | text: fired / not fired, derived count |
| Datalog fact | `DA` of the fact line (present in a solve; not "used") | `<line>` | listed per solve |

`check-formats.py`: `lcov.info`, `lcov-per-test.info` (12,948 records for flix-invaders) and `cobertura.xml` (2297 line elements) have 0 violations of the record invariants (`LF`, `LH`, `FNF`, `FNH`, `BRF`, `BRH`) and of `coverage-04.dtd` (sha256 `e467cb8252e20feb794fc2dedfbea7b4957921669c758f0282d525df88897917`, fetched from `cobertura/cobertura`); the DTD check is on elements, children and required attributes. `lcov`, `genhtml` and `xmllint` are not installed: that a real consumer accepts the files is UNKNOWN. The own report carries the mutation vocabulary (`not covered`, one line per item, then a summary), which flixw-mutate already prints.

Evaluation as targets: LCOV is the smallest to write and has the per-test record; its partial-line gap is closed by `BRDA` only for `if`/`match`. Cobertura is the format most CI viewers read and has no per-test form. A per-test view needs the own format or LCOV `TN:`. The Datalog part fits LCOV and Cobertura only by treating a rule as a function and its line as a line.

## 4. Recommendation: shape of the shared core

What the engine of #18/#20 already has and the spike reused unchanged in idea: a marker that records ids into JDK arrays, a baseline run that clears and reads them per test, and `Coverage` (test to a set of site ids). Coverage plugin and mutation plugin differ in where the sites come from and what is done with the table.

| Piece | Shape | Mutation (#18) | Coverage (this spike) |
| --- | --- | --- | --- |
| Site | `Site{id, source, kind, file, span, line, parent}`; one dense id space per run, ids sorted by file and offset (stable between runs) | operator site and operands, mutants point at site ids | region (entry, then, else, arm, lambda) and expression starts for lines; Datalog: rule and fact sites from the typed AST constraint sets |
| Marker | one Java class, state in JDK arrays published by the worker, `hit(n)` returns `false` | `FlixwMutant.on(n)` returns `n == active` | coverage can call the existing `on(n)` (never the active mutant) or `hit(n)`; the spike used `CovMark` with the same arrays. With the engine's `Marker`: not run |
| Rendering | text rewrite of a copy, line numbers kept | `Schemata.render`: sites nest only in operands | wrap in `(if (hit(n)) bug!(..) else { E })`; one renderer for both kinds of site, nested: UNKNOWN (not built) |
| Worker | compile and load once, run each test alone, clear before and read after | baseline, then `run <n> <tests>` | baseline only; for Datalog a second worker kind: isolated class loader with the patched `Solver.flix` (`GenLauncher`) |
| Attribution | `Hits{test -> site id -> level}`; level 1 = reached, 2 = fired (Datalog tuples derived) | `Coverage` today: ids only | same table, `BitSet` per test (501 tests x 1256 ids is about 80 KB) |
| Reporter | functions of `(sites, hits)` | `Report` | LCOV, Cobertura, own |

Two sources behind that table:

1. Source marker (functional): sites from the typed AST, text rewrite, hits from the arrays. Measured: lines, branches, functions exact on the fixture; overhead 0 to 22 % in microbenchmarks, 12 % (medians) on flix-invaders.
2. Solver hook (Datalog): sites from the typed AST constraint sets (rule signature and line), hits from the hook's records; the mapping of a rule instance to a site is by signature inside the test's def (419 of 432 unique in the Flix test files). The hit level is "reached" from presence, "fired" with provenance.

Decisions for the owner, with a recommendation:

- Coverage compile: separate from the mutant compile at first (one more compile of 2 to 12 s depending on the machine load, no mixed code shapes; marker form and mutation helpers do not interact). A single program with both kinds of site saves that compile, needs the combined renderer and the check that the optimiser keeps both: UNKNOWN.
- Datalog level in the first version: presence plus relation sizes, without the provenance solve (no hang, no second solve); provenance as an option with a documented limit (cyclic programs) or not at all until the own body evaluation is built.
- Lines: expression-start model as in the spike (needs the typed-AST walk anyway); lambda regions on, loop bodies and lazy operands as listed limits, or regions for `and`/`or`, `foreach` and handler bodies as the next step (not measured).
- Output: own report and LCOV first (per-test `TN:`), Cobertura after.
- Ids: keep the sorted numbering; an id is stable only for unchanged sources.

## 5. Open risks

- The typed-AST spans are not always expression text (1.6). Regions are dropped when detected; a span that is balanced but wrong would compile and report wrongly. 76 of 1332 candidate regions on flix-invaders were dropped by the two guards; whether others are wrong is UNKNOWN (no ground truth there).
- The optimiser: markers are kept in the form used, 87 + 13 regions of flix-invaders are missing from the bytecode (cause of 13 not isolated); other forms of marker were not tried.
- Line model: expression starts decide what a line is; `and`/`or` operands, loop bodies, handlers, `try`, string interpolation and `for` desugaring are not measured beyond the two limits listed. Lines read H where part of the line did not run.
- Tests that start threads, share Java state, or time out: not run here (as in #16). A test that times out in the baseline loses its hit set.
- The hook is tied to Fixpoint3 internals; the provenance solve does not terminate on some cyclic programs and fails on lattice programs.
- Rule-to-source mapping uses predicate names; two identical rules in one def stay ambiguous (13 of 432 instances: 11 in `Solve.Simple`, 2 in `PSolve`), and facts on one line cannot be told apart.
- Per-test LCOV is large (27.6 MB for 501 tests); a per-test binary form would be smaller.
- Timings come from a shared, loaded machine and a single Linux aarch64 VM.

## 6. Not run (UNKNOWN)

Flix versions other than 0.79.0; other operating systems; lattices (excluded); the engine with coverage sites built in (no engine change; the engine's `Marker` as the target); both site kinds in one program; `and`/`or`, loop, handler and `try` regions; trait default implementations (`Sig`); fact use at tuple level; JaCoCo (excluded); a real LCOV or Cobertura consumer; accuracy of line figures on flix-invaders; counts per region (boolean markers only); tests that spawn threads; a full mutation run (not part of this spike).
