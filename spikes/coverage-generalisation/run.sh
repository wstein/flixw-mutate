#!/bin/sh
# Spike #22, functional part: source markers from the typed AST (function entry, if branch, match arm, lambda body),
# per-test hit sets, lines/branches/functions, LCOV, Cobertura XML and a text report. Engine classes unchanged.
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-generalisation/run.sh [<project dir> [expect]]
#   default: fixture/ with its line annotations as ground truth (exit 1 on a deviation)
# Environment: TIMEOUT_MS (default 5000 per test), REPS (default 3), JAVA_OPTS (-Dcov.lambdas=false, -Dcov.plain=false)
set -eu
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$here/../..
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
jar=$(sh "$here/../common/fetch-flix.sh" "$work/jars")
classes=$work/classes-gen
rm -rf "$classes"; mkdir -p "$classes" "$work/support"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$repo/src/main/java/dev/flixw/mutate" "$repo/spikes/common/java" "$repo/spikes/solver-hook/java/dev/flixw/spike/PatchSolver.java" "$here/java" -name '*.java' ! -name CovNop.java ! -name CovMark.java)
rm -rf "$work/covcls"; mkdir -p "$work/covcls"
javac -d "$work/covcls" "$here/java/dev/flixw/spike/CovMark.java" "$here/java/dev/flixw/spike/CovNop.java"
(cd "$work/covcls" && jar --create --file "$work/support/cov.jar" dev/flixw/spike/CovMark.class && jar --create --file "$work/support/nop.jar" dev/flixw/spike/CovNop.class)
if [ "$#" -gt 0 ]; then p=$1; shift; else p=$here/fixture; set -- expect; fi
name=$(basename "$p")
echo "== $p"
echo "load average before: $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || uptime)"
# shellcheck disable=SC2086
java -Xmx${XMX:-1500m} -Xss16m ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.mutate.GenRun "$p" "$work/run-$name" \
  "$work/support/cov.jar" "${TIMEOUT_MS:-5000}" "${REPS:-3}" "$@" 2> "$work/stderr-$name.log"
