#!/bin/sh
# Spike #1 part 1: hook Fixpoint3.Solver.runSolverInternal without changing Flix 0.79.0.
#
# Usage: SPIKE_WORK=<empty-or-scratch-dir> sh spikes/solver-hook/run.sh [fixture|material]
#   fixture   (default) both routes on the synthetic fixture; exit 1 if a planted mutant has another outcome
#   material  additionally the Flix test files and examples at the pinned revision (fetched, not copied)
# Optional: JAVA_OPTS (e.g. -Dspike.sabotage=nomutate, a deliberately wrong run), HOOK_ARGS=nocheck (hook's
# stratification check off).
# Needs JDK 21 (java, javac, jar), curl, sha256sum, unzip not required. Never commits a jar.
set -eu
umask 022
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
common=$here/../common
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mode=${1:-fixture}
mkdir -p "$work"
jar=$(sh "$common/fetch-flix.sh" "$work/jars")
classes=$work/classes
rm -rf "$classes"; mkdir -p "$classes"
# shellcheck disable=SC2046
javac -cp "$jar" -d "$classes" $(find "$common/java" "$here/java" -name '*.java')
(cd "$classes" && jar --create --file "$work/support.jar" dev/flixw/spike/Splice.class)
status=0
for route in a b; do
  echo "== fixture, route $route"
  # shellcheck disable=SC2086  # JAVA_OPTS and HOOK_ARGS are word lists on purpose
  java -Xmx3g ${JAVA_OPTS:-} -cp "$classes:$jar" dev.flixw.spike.Launcher "$route" "$jar" "$classes" "$work/work-$route" \
    "$here/fixture" "$here/mutants.tsv" "$here/hook/Hook.flix" "${HOOK_ARGS:-nocheck-off}" "$work/support.jar" || status=1
done
if [ "$mode" = material ]; then
  sh "$common/fetch-flix-material.sh" "$work/material" >/dev/null
  for f in "$work"/material/Test.Exp.Fixpoint.*.flix "$work"/material/[a-z]*.flix; do
    d=$work/one; rm -rf "$d"; mkdir -p "$d/src" "$d/test"
    case $f in */Test.*) cp "$f" "$d/test/";; *) cp "$f" "$d/src/";; esac
    for route in a b; do
      echo "== material $(basename "$f"), route $route"
      java -Xmx3g -Dspike.max=40 -Dspike.timeout=8000 -cp "$classes:$jar" dev.flixw.spike.Launcher "$route" "$jar" "$classes" \
        "$work/work-$route" "$d" auto "$here/hook/Hook.flix" x "$work/support.jar" 2>&1 \
        | sed 's/\x1b\[[0-9;]*m//g' | grep -E '^(route|baseline|generated|counts|RESULT)|^>>|Exception' | cut -c1-220 || true
    done
  done
fi
exit $status
