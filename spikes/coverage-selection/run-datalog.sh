#!/bin/sh
# Spike #16, part B: test selection for Datalog mutants with the solver hook (route b of spikes/solver-hook).
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-selection/run-datalog.sh [fixture|material]
# Environment: MAX (mutants per file, default 40), TIMEOUT_MS (per test, default 30000), JAVA_OPTS
# (-Dspike.sabotage=nomarks: no test reaches any mutant, the run must fail)
set -eu
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
hooks=$here/../solver-hook
common=$here/../common
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mode=${1:-fixture}
mkdir -p "$work"
jar=$(sh "$common/fetch-flix.sh" "$work/jars")
classes=$work/classes-dl
rm -rf "$classes"; mkdir -p "$classes"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$common/java" "$hooks/java" "$here/java/dev/flixw/spike" -name '*.java' ! -name Cov.java ! -name CovState.java)
status=0
run() { # <project dir> <label>
  # shellcheck disable=SC2086
  java -Xmx3g ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.CovLauncher "$jar" "$classes" "$work/dl-$2" \
    "$hooks/hook/Hook.flix" "$1" "${MAX:-40}" "${TIMEOUT_MS:-30000}" x 2> "$work/stderr-dl-$2.log" || status=1
}
echo "load average before: $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || uptime)"
run "$hooks/fixture" fixture
if [ "$mode" = material ]; then
  sh "$common/fetch-flix-material.sh" "$work/material" >/dev/null
  for n in If PSolve Solve.Simple Solve.Project Stratification; do
    d=$work/one-$n; rm -rf "$d"; mkdir -p "$d/test"
    cp "$work/material/Test.Exp.Fixpoint.$n.flix" "$d/test/"
    run "$d" "Test.Exp.Fixpoint.$n"
  done
fi
exit $status
