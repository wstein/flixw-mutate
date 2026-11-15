#!/bin/sh
# Engine Datalog acceptance. Default: fixture. --material also fetches the pinned Flix test sources
# and compares one mutant per available operator and file. No full mutation sweep is run.
set -eu
umask 022
case ${1:-} in
  '') [ "$#" -eq 0 ] || { echo 'usage: test-datalog-acceptance.sh [--material]' >&2; exit 2; } ;;
  --material) [ "$#" -eq 1 ] || { echo 'usage: test-datalog-acceptance.sh [--material]' >&2; exit 2; } ;;
  *) echo 'usage: test-datalog-acceptance.sh [--material]' >&2; exit 2 ;;
esac
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/datalog-acceptance.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$work/classes"
quote() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/^/"/' -e 's/$/"/'; }
find "$root/src/main/java/dev/flixw/mutate" "$root/src/test/java" -name '*.java' | LC_ALL=C sort | quote > "$work/sources"
javac -Xlint:all -Werror --release 21 -d "$work/classes" @"$work/sources"
FLIXW_TEST_FIXTURES=$root/src/test/fixtures
FLIXW_TEST_WIDE=$root/spikes/coverage-selection/fixture-wide
FLIXW_TEST_COVERAGE_SPIKE=$root/spikes/coverage-generalisation
FLIXW_TEST_SOLVER_SPIKE=$root/spikes/solver-hook
FLIXW_TEST_FLIX_JAR=$(sh "$root/spikes/common/fetch-flix.sh" "${FLIXW_TEST_JARS:-$work/jars}")
export FLIXW_TEST_FIXTURES FLIXW_TEST_WIDE FLIXW_TEST_COVERAGE_SPIKE FLIXW_TEST_SOLVER_SPIKE FLIXW_TEST_FLIX_JAR
if [ "${1:-}" = --material ]; then
  FLIXW_DL_MATERIAL=$work/material
  sh "$root/spikes/common/fetch-flix-material.sh" "$FLIXW_DL_MATERIAL" >/dev/null
  export FLIXW_DL_MATERIAL
else
  unset FLIXW_DL_MATERIAL
fi
java -cp "$work/classes:$FLIXW_TEST_FLIX_JAR" dev.flixw.mutate.DatalogAcceptanceTest
