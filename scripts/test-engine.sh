#!/bin/sh
# Engine tests: JDK only, no SDK needed. Compiles src/main/java/dev/flixw/mutate and
# src/test/java, runs every *Test class (each prints "<suite>: n passed, m failed").
# Tests that need the Flix compiler fetch the pinned flix.jar (digest-checked, spikes/common/fetch-flix.sh).
set -eu
umask 022
case ${1:-} in
  "") [ "$#" -eq 0 ] || { echo "usage: test-engine.sh [--fixtures]" >&2; exit 2; }; suites=all ;;
  --fixtures) [ "$#" -eq 1 ] || { echo "usage: test-engine.sh [--fixtures]" >&2; exit 2; }; suites=fixtures ;;
  *) echo "usage: test-engine.sh [--fixtures]" >&2; exit 2 ;;
esac
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate engine.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# The JAVA_TOOL_OPTIONS value set below puts the path in double quotes. The JVM (probed with JDK 21) carries a single
# quote and a backslash inside them, but ends the value at a double quote ("Unmatched quote") and splits it at a line
# break: only those two are refused here (checked first, before any tool sees the path).
case $work in
  *\"* | *'
'*) echo "test-engine: TMPDIR contains a double quote or a newline: $work" >&2; exit 2 ;;
esac
# _JAVA_OPTIONS and JDK_JAVA_OPTIONS take precedence over JAVA_TOOL_OPTIONS (probed with JDK 21): a java.io.tmpdir in
# either would send the temporary files of the suites elsewhere and the leak check below would find nothing.
for var in _JAVA_OPTIONS JDK_JAVA_OPTIONS; do
  eval "val=\${$var:-}"
  # The JDK joins quoted fragments and preserves whitespace inside quotes. Feed awk on stdin so it does not
  # interpret backslashes in the option value. Option files remain opaque to this guard.
  problem=$(printf '%s\n' "$val" | awk '
    function check() {
      if (token == "-Djava.io.tmpdir" || index(token, "-Djava.io.tmpdir=") == 1) return "property"
      if (substr(token, 1, 1) == "@" || token == "-XX:VMOptionsFile" || index(token, "-XX:VMOptionsFile=") == 1) return "file"
      return ""
    }
    { if (NR > 1) input = input "\n"; input = input $0 }
    END {
      quote = ""; token = ""
      for (i = 1; i <= length(input); i++) {
        c = substr(input, i, 1)
        if (quote != "") { if (c == quote) quote = ""; else token = token c }
        else if (c == "\"" || c == sprintf("%c", 39)) quote = c
        else if (c ~ /[[:space:]]/) {
          result = check(); if (result != "") { print result; exit }
          token = ""
        } else token = token c
      }
      result = check(); if (result != "") print result
    }
  ')
  case $problem in
    property) echo "test-engine: $var sets java.io.tmpdir, which would hide leftovers: unset it" >&2; exit 2 ;;
    file) echo "test-engine: $var cannot verify java.io.tmpdir through an options file: unset it" >&2; exit 2 ;;
  esac
done
# javac reads an @argfile with whitespace separation unless a path is quoted (as package.sh does).
quote() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/^/"/' -e 's/$/"/'; }
find "$root/src/main/java/dev/flixw/mutate" "$root/src/test/java" -name '*.java' | LC_ALL=C sort | quote > "$work/sources.txt"
mkdir -p "$work/classes"
# Tests that need the Flix compiler find the fixtures and the jar through these variables.
FLIXW_TEST_FIXTURES=$root/src/test/fixtures
# The wide fixture of the coverage spike (selection tests).
FLIXW_TEST_WIDE=$root/spikes/coverage-selection/fixture-wide
# The ground-truth files of the coverage spike (site table tests).
FLIXW_TEST_COVERAGE_SPIKE=$root/spikes/coverage-generalisation
# The fixture and the planted mutants of the solver-hook spike (Datalog mutant tests).
FLIXW_TEST_SOLVER_SPIKE=$root/spikes/solver-hook
FLIXW_TEST_FLIX_JAR=$(sh "$root/spikes/common/fetch-flix.sh" "${FLIXW_TEST_JARS:-$work/jars}")
export FLIXW_TEST_FIXTURES FLIXW_TEST_WIDE FLIXW_TEST_COVERAGE_SPIKE FLIXW_TEST_SOLVER_SPIKE FLIXW_TEST_FLIX_JAR
javac -Xlint:all -Werror --release 21 -d "$work/classes" @"$work/sources.txt"
total=0
bad=0
# Each suite gets its own empty java.io.tmpdir; what is left in it afterwards is a leak (see test-engine-leaks.sh).
# The property reaches the JVMs a suite starts through JAVA_TOOL_OPTIONS, appended to the caller's value (the last -D
# wins, so a java.io.tmpdir of the caller does not hide a leak). The JVM
# prints "Picked up JAVA_TOOL_OPTIONS: ..." on stderr for every JVM that reads it; that line cannot be switched off,
# so it appears once per suite and once per child JVM (stdout and the results are not affected).
leaks=0
if [ "$suites" = fixtures ]; then
  tests="SchemataCompileTest SchemataTest EngineTest DatalogHitsTest DatalogEdgeTest DatalogMutantsTest OrderTest"
else
  tests=$(cd "$root/src/test/java/dev/flixw/mutate" && ls ./*Test.java | sed 's|^\./||; s|\.java$||')
fi
for t in $tests; do
  mkdir "$work/tmp-$t"
  if JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=\"$work/tmp-$t\"" java -cp "$work/classes:$FLIXW_TEST_FLIX_JAR" "dev.flixw.mutate.$t" > "$work/out"; then
    total=$((total + $(tail -n 1 "$work/out" | awk '{print $2}')))
  else
    bad=$((bad + 1)); echo "test-engine: $t failed" >&2
  fi
  cat "$work/out"
  left=$(ls -A "$work/tmp-$t" | wc -l | tr -d ' ')
  if [ "$left" -ne 0 ]; then
    leaks=$((leaks + left)); echo "test-engine: $t left $left entries in its temp directory:" >&2; ls -A "$work/tmp-$t" >&2
  fi
done
if [ "$leaks" -ne 0 ]; then echo "test-engine: $leaks leftover temp entries" >&2; exit 1; fi
if [ "$bad" -ne 0 ]; then echo "test-engine: $bad suite(s) failed" >&2; exit 1; fi
echo "test-engine: $total passed, 0 failed"
