#!/bin/sh
# Spike #1 part 2: mutant schemata (design option 4) on Flix 0.79.0.
# Usage: SPIKE_WORK=<scratch dir> [RUNS=5] [TIMEOUT_MS=3000] sh spikes/mutant-schemata/run.sh
# JAVA_OPTS=-Dspike.sabotage=noswitch runs a deliberately wrong engine (the switch is never set).
# Exit 1 if a planted site has another outcome, or if compile-per-mutant and schemata disagree.
set -eu
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
common=$here/../common
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
jar=$(sh "$common/fetch-flix.sh" "$work/jars")
classes=$work/classes-schemata
rm -rf "$classes"; mkdir -p "$classes"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$common/java" "$here/java" -name '*.java')
echo "load average before: $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null || uptime)"
# shellcheck disable=SC2086
java -Xmx2g ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.SchemataRun "$here/fixture" "$here/mutants.tsv" "$work/run" \
  "${RUNS:-5}" "${TIMEOUT_MS:-3000}"
