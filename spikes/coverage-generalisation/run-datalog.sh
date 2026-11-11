#!/bin/sh
# Spike #22, Datalog part: rules and facts per test through the solver hook (route b of ../solver-hook), with a second
# solve with provenance per solve of the program. Engine classes unchanged.
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-generalisation/run-datalog.sh [<project dir> [expect]]
#   default: datalog-fixture/ with expected-rules.tsv and expected-facts.tsv as ground truth (exit 1 on a deviation)
# Environment: TIMEOUT_MS (default 20000 per test), XMX (default 1500m), HOOK (default datalog/CovHook.flix)
set -eu
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$here/../..
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
jar=$(sh "$here/../common/fetch-flix.sh" "$work/jars")
classes=$work/classes-dl
rm -rf "$classes"; mkdir -p "$classes"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$repo/src/main/java/dev/flixw/mutate" "$repo/spikes/common/java" "$repo/spikes/solver-hook/java/dev/flixw/spike/PatchSolver.java" "$here/java" -name '*.java' ! -name CovNop.java ! -name CovMark.java)
if [ "${1:-}" = material ]; then
  # Real Flix test files (fetched, digest-checked, not copied): one JVM per file and configuration, with the hook and
  # with the stock library (run-time baseline). Files: MATERIAL (default: the Test.Exp.Fixpoint files below).
  sh "$here/../common/fetch-flix-material.sh" "$work/material" >/dev/null
  status=0
  for f in ${MATERIAL:-If PSolve Solve.Simple Solve.Project Stratification Query PQuery Inject Functional Compose Permutations Application}; do
    src=$work/material/Test.Exp.Fixpoint.$f.flix
    d=$work/mat-$f; rm -rf "$d"; mkdir -p "$d/test"; cp "$src" "$d/test/"
    echo "== Test.Exp.Fixpoint.$f"
    # shellcheck disable=SC2086
    java -Xmx"${XMX:-1500m}" -Xss16m -Ddl.quiet=true -Ddl.reps="${REPS:-3}" ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.GenLauncher \
      "$jar" "$classes" "$work/dl-$f" "${HOOK:-$here/datalog/CovHook.flix}" dev.flixw.mutate.DatalogGen "$d" "${TIMEOUT_MS:-20000}" 2> "$work/stderr-dl-$f.log" || { echo "FAIL (hook run), see $work/stderr-dl-$f.log"; status=1; }
    # shellcheck disable=SC2086
    java -Xmx"${XMX:-1500m}" -Xss16m -Ddl.quiet=true -Ddl.stock=true -Ddl.reps="${REPS:-3}" ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.GenLauncher \
      "$jar" "$classes" "$work/dl-stock-$f" none dev.flixw.mutate.DatalogGen "$d" "${TIMEOUT_MS:-20000}" 2> "$work/stderr-dl-stock-$f.log" | grep -a "^summary" | sed 's/.*; suite/stock: suite/' || true
  done
  exit $status
fi
if [ "$#" -gt 0 ]; then p=$1; shift; else p=$here/datalog-fixture; set -- expect; fi
name=$(basename "$p")
echo "== $p"
exec java -Xmx"${XMX:-1500m}" -Xss16m ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.GenLauncher \
  "$jar" "$classes" "$work/dl-$name" "${HOOK:-$here/datalog/CovHook.flix}" dev.flixw.mutate.DatalogGen "$p" "${TIMEOUT_MS:-20000}" "$@" 2> "$work/stderr-dl-$name.log"
