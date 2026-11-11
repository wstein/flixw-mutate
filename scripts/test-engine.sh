#!/bin/sh
# Engine tests: JDK only, no SDK needed. Compiles src/main/java/dev/flixw/mutate and
# src/test/java, runs every *Test class (each prints "<suite>: n passed, m failed").
# Tests that need the Flix compiler fetch the pinned flix.jar (digest-checked, spikes/common/fetch-flix.sh).
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate engine.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# javac reads an @argfile with whitespace separation unless a path is quoted (as package.sh does).
quote() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/^/"/' -e 's/$/"/'; }
find "$root/src/main/java/dev/flixw/mutate" "$root/src/test/java" -name '*.java' | LC_ALL=C sort | quote > "$work/sources.txt"
mkdir -p "$work/classes"
# Tests that need the Flix compiler find the fixtures and the jar through these variables.
FLIXW_TEST_FIXTURES=$root/src/test/fixtures
FLIXW_TEST_FLIX_JAR=$(sh "$root/spikes/common/fetch-flix.sh" "${FLIXW_TEST_JARS:-$work/jars}")
export FLIXW_TEST_FIXTURES FLIXW_TEST_FLIX_JAR
javac -Xlint:all -Werror --release 21 -d "$work/classes" @"$work/sources.txt"
total=0
bad=0
for t in $(cd "$root/src/test/java/dev/flixw/mutate" && ls ./*Test.java | sed 's|^\./||; s|\.java$||'); do
  if java -cp "$work/classes:$FLIXW_TEST_FLIX_JAR" "dev.flixw.mutate.$t" > "$work/out"; then
    total=$((total + $(tail -n 1 "$work/out" | awk '{print $2}')))
  else
    bad=$((bad + 1)); echo "test-engine: $t failed" >&2
  fi
  cat "$work/out"
done
if [ "$bad" -ne 0 ]; then echo "test-engine: $bad suite(s) failed" >&2; exit 1; fi
echo "test-engine: $total passed, 0 failed"
