#!/bin/sh
# Spike #16, part A: marker coverage and test selection on schemata sites (engine classes unchanged).
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-selection/run-schemata.sh [<project dir>...]
#   default projects: the engine's synthetic fixture (src/test/fixtures/calc) and fixture-wide/ of this spike
# Environment: TIMEOUT_MS (default 3000 per test), REPS (default 7), JAVA_OPTS (e.g. -Dspike.sabotage=nomarks:
# markers never record, the run must fail)
set -eu
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
repo=$here/../..
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
jar=$(sh "$here/../common/fetch-flix.sh" "$work/jars")
classes=$work/classes-cov
rm -rf "$classes"; mkdir -p "$classes" "$work/support"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$repo/src/main/java/dev/flixw/mutate" "$repo/spikes/common/java" "$here/java" -name '*.java' ! -name Cov.java ! -name DatalogCoverage.java ! -name CovLauncher.java)
# Cov goes into the support jar only: with a copy on the class path too, Flix would still load its own.
rm -rf "$work/covcls"; mkdir -p "$work/covcls"
javac -d "$work/covcls" "$here/java/dev/flixw/spike/Cov.java"
(cd "$work/covcls" && jar --create --file "$work/support/cov.jar" dev/flixw/spike/Cov.class)
[ "$#" -gt 0 ] || set -- "$repo/src/test/fixtures/calc" "$here/fixture-wide"
status=0
for p in "$@"; do
  echo "== $p"
  echo "load average before: $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || uptime)"
  # shellcheck disable=SC2086
  java -Xmx2g ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.mutate.CoverageRun "$p" "$work/run-$(basename "$p")" \
    "$work/support/cov.jar" "${TIMEOUT_MS:-3000}" "${REPS:-7}" 2> "$work/stderr-$(basename "$p").log" || status=1
done
exit $status
