# Test ordering on flix-invaders

One bounded observation for [#23](https://github.com/wstein/flixw-mutate/issues/23), 15 November 2026. The 12 covered mutants had the same outcome in all three runs: eight killed and four survived. No run timed out. This is one observation per mode, not a general benchmark or proof of selection correctness.

| Mode | Mutant execution | Tests executed | Session open, including baseline | Baseline |
| --- | ---: | ---: | ---: | ---: |
| Selected, ordered | 6,690 ms | 332 | 15,717 ms | 6,949 ms |
| Selected, name order | 12,992 ms | 286 | 15,330 ms | 7,285 ms |
| All tests, name order | 44,808 ms | 3,220 | 15,237 ms | 6,979 ms |

The ordered run took less time despite executing more tests than the name-ordered selected run: tests have different costs, and a run stops at its first failure. Session opening includes source processing, compilation, loading and baseline; execution excludes that opening but includes any worker reload after a timeout. There were no reloads due to timeouts here. Each mode used a fresh session, so JIT warmup, runtime scheduling and machine load differ. Mode order was ordered, unordered, full; there were no repeated or reversed-order measurements.

The unchanged project wrapper ran 501 tests successfully (0 failed, 0 skipped); the 465 in #9 is an earlier count. The engine baseline also found 501 tests, 890 functional mutants, and in `src/Invaders/` 544 covered and 2 uncovered mutants. The sample consists of the first 12 covered identities from the pinned production area, ordered by file and identity in rounds, one per file before the next round. It was fixed from baseline coverage before running any mutant; the two uncovered mutants were counted separately, not used to measure ordering. Thus this sample does not estimate the entire project. Tests include long game simulations. The project tests use explicit seeds; there is no additional random sample seed.

Each mutant request had the same 120,000 ms timeout for the entire selected test list, not per test. This was fixed before the runs: the full unmutated baseline took 8,171 ms and its longest individual test 2,626 ms in the preceding probe. Timeouts kill and reload the worker; test counts for a timeout are upper bounds and a timeout cannot establish the actual outcome. Killed mutants may name different first failing tests and run different numbers of tests; these differences are not outcome mismatches.

| ID | Project file and position | Change | Outcome | Tests: ordered / unordered / full | ms: ordered / unordered / full |
| ---: | --- | --- | --- | --- | --- |
| 107 | `src/Invaders/Bunkers.flix:109:59` | `-` → `+` | killed | 1 / 1 / 63 | 4 / 3 / 2042 |
| 154 | `src/Invaders/Collide.flix:23:30` | `-` → `+` | killed | 1 / 5 / 47 | 2 / 1178 / 998 |
| 165 | `src/Invaders/Demo.flix:128:17` | `not` → `(removed)` | killed | 1 / 5 / 107 | 0 / 250 / 238 |
| 299 | `src/Invaders/Game.flix:110:20` | `/` → `*` | survived | 208 / 208 / 501 | 6410 / 6124 / 6077 |
| 413 | `src/Invaders/Screens.flix:100:42` | `-` → `+` | survived | 2 / 2 / 501 | 1 / 0 / 6058 |
| 449 | `src/Invaders/Session.flix:105:17` | `>` → `>=` | killed | 1 / 1 / 348 | 0 / 0 / 5970 |
| 489 | `src/Invaders/Sprites.flix:103:13` | `+` → `-` | killed | 1 / 1 / 434 | 0 / 1 / 6069 |
| 503 | `src/Invaders/Types.flix:184:20` | `+` → `-` | survived | 3 / 3 / 501 | 41 / 43 / 6026 |
| 557 | `src/Invaders/View.flix:105:29` | `+` → `-` | survived | 35 / 35 / 501 | 63 / 69 / 6104 |
| 108 | `src/Invaders/Bunkers.flix:152:21` | `/` → `*` | killed | 2 / 5 / 53 | 1 / 1957 / 1870 |
| 155 | `src/Invaders/Collide.flix:24:30` | `-` → `+` | killed | 1 / 5 / 47 | 1 / 1083 / 1037 |
| 166 | `src/Invaders/Demo.flix:132:47` | `+` → `-` | killed | 76 / 15 / 117 | 159 / 2274 / 2308 |

Pins: flix-invaders `5df5ac1312b6216a474ab80bf7dd6556f335e238`, package 0.2.0; measurement code `afe46773257de8b3601ed7a27c4994894945f7ad` (parent `063838af04286afcecc5d7c641a95591424c1bae`); Flix 0.79.0 SHA-256 `8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf`, matching the project lock; Temurin JDK 21.0.13+9; SDK source revision `515b8080336bf9f2ab6bc8ec2e87b29858c69eb7` (the measurement compiles the engine directly and does not use SDK classes). Linux aarch64, 10 logical CPUs, 9.8 GiB memory; 1-minute load averages at the three starts 2.52, 3.14 and 3.01. Other machine activity was not controlled.

## Reproduce

Use clean checkouts of the pins above and a JDK 21. Set `PROJECT` to the Invaders checkout, `JAVA_HOME` to the JDK, `FLIX_JAR` to the 0.79.0 jar, and `OUT` to an empty scratch directory outside either checkout. From the mutate checkout at the measurement code SHA:

```sh
umask 022
sha256sum "$FLIX_JAR" # compare with the lock and digest above
(cd "$PROJECT" && FLIX_JAVA_HOME="$JAVA_HOME" JAVA_TOOL_OPTIONS=-Djava.awt.headless=true ./flixw test)
mkdir -p "$OUT/classes"
find "$PWD/src/main/java/dev/flixw/mutate" -name '*.java' | LC_ALL=C sort > "$OUT/sources"
printf '%s\n' "$PWD/src/test/java/dev/flixw/mutate/InvadersMeasurement.java" >> "$OUT/sources"
"$JAVA_HOME/bin/javac" --release 21 -d "$OUT/classes" @"$OUT/sources"
CP="$OUT/classes:$FLIX_JAR"
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true "$JAVA_HOME/bin/java" -cp "$CP" dev.flixw.mutate.InvadersMeasurement probe "$PROJECT" "$JAVA_HOME/bin/java" "$CP" 120000 12 "$OUT/sample.tsv"
for mode in ordered unordered full; do
  JAVA_TOOL_OPTIONS=-Djava.awt.headless=true "$JAVA_HOME/bin/java" -cp "$CP" dev.flixw.mutate.InvadersMeasurement "$mode" "$PROJECT" "$JAVA_HOME/bin/java" "$CP" 120000 12 "$OUT/sample.tsv" > "$OUT/$mode.txt" || break
done
"$JAVA_HOME/bin/java" -cp "$CP" dev.flixw.mutate.InvadersMeasurement compare "$OUT/sample.tsv" "$OUT/ordered.txt" "$OUT/unordered.txt" "$OUT/full.txt"
```

The compiler CLI baseline uses the project's `./flixw`; the separate measurement uses the mutate engine API and the same digest-verified compiler jar. The probe refuses to replace an existing manifest. Each mode checks its baseline-selected identities against that manifest before running mutants. The comparison rejects missing or reordered rows, compares outcome classes and allows a different killer or test count. A `NOT_COVERED` selected outcome is compared to `SURVIVED` in a full run; timeout differences are reported, not converted to success. Raw output records the killer, count, elapsed time and whether a count is a timeout upper bound. Paths in the example must not contain spaces because the javac argument file lists paths without quoting.
