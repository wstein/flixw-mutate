#!/bin/sh
# The leftover check of test-engine.sh, tested on a copy of the script with fixture suites: a suite that leaves a
# file in java.io.tmpdir must fail the run and be named; a clean suite must pass; a JAVA_TOOL_OPTIONS of the caller
# must stay in effect; a TMPDIR the quoting cannot carry must stop the run with a message.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate leaks.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
checks=0
fail() { echo "test-engine-leaks: $*" >&2; exit 1; }
# <name> <body of main>: a fixture suite that prints the line test-engine.sh reads.
suite() {
  printf 'package dev.flixw.mutate;\npublic final class %s {\n    public static void main(String[] a) throws Exception {\n%s\n        System.out.println("%s: 1 passed, 0 failed");\n    }\n}\n' "$1" "$2" "$1" > "$fx/src/test/java/dev/flixw/mutate/$1.java"
}
# A fresh fixture tree with a copy of the script and no suites.
fixture() {
  fx=$work/fx$checks
  mkdir -p "$fx/scripts" "$fx/spikes/common" "$fx/src/main/java/dev/flixw/mutate" "$fx/src/test/java/dev/flixw/mutate" "$fx/tmp"
  cp "$root/scripts/test-engine.sh" "$fx/scripts/"
  # No compiler jar is needed: the fixture suites do not use it.
  printf '#!/bin/sh\ntouch "$1.jar"\necho "$1.jar"\n' > "$fx/spikes/common/fetch-flix.sh"
  printf 'package dev.flixw.mutate;\nfinal class Fixture { }\n' > "$fx/src/main/java/dev/flixw/mutate/Fixture.java"
}
LEAK='        java.nio.file.Files.writeString(java.nio.file.Files.createTempFile("leak", ".txt"), "x");'
CLEAN='        java.nio.file.Files.delete(java.nio.file.Files.createTempFile("clean", ".txt"));'
# <extra env assignments...> are passed before sh; the run's streams go to $fx/out and $fx/err, the status to $status.
run() {
  set +e
  TMPDIR="$fx/tmp" "$@" sh "$fx/scripts/test-engine.sh" > "$fx/out" 2> "$fx/err"
  status=$?
  set -e
}

fixture; suite CleanTest "$CLEAN"; run env
[ "$status" -eq 0 ] || { cat "$fx/err" >&2; fail "a clean suite failed the run (status $status)"; }
grep -q '^test-engine: 1 passed, 0 failed$' "$fx/out" || fail 'a clean suite: no summary line'
checks=$((checks + 1))

fixture; suite CleanTest "$CLEAN"; suite LeakTest "$LEAK"; run env
[ "$status" -eq 1 ] || { cat "$fx/err" >&2; fail "a leaking suite: status $status, expected 1"; }
grep -q '^test-engine: LeakTest left 1 entries' "$fx/err" || { cat "$fx/err" >&2; fail 'a leaking suite was not named'; }
! grep -q 'CleanTest left' "$fx/err" || fail 'a clean suite was named as leaking'
grep -q '^test-engine: 1 leftover temp entries$' "$fx/err" || fail 'a leaking suite: no total'
checks=$((checks + 1))

# The caller's JAVA_TOOL_OPTIONS stays in effect next to the temporary directory.
fixture; suite OptionsTest '        if (!"1".equals(System.getProperty("flixw.fixture"))) System.exit(1);'
run env JAVA_TOOL_OPTIONS=-Dflixw.fixture=1
[ "$status" -eq 0 ] || { cat "$fx/err" >&2; fail "JAVA_TOOL_OPTIONS of the caller was not kept (status $status)"; }
checks=$((checks + 1))

# A TMPDIR with a quote cannot be carried by JAVA_TOOL_OPTIONS: the run stops and says why.
fixture; suite CleanTest "$CLEAN"
mkdir "$work/odd\"dir"
set +e; TMPDIR="$work/odd\"dir" sh "$fx/scripts/test-engine.sh" > "$fx/out" 2> "$fx/err"; status=$?; set -e
[ "$status" -ne 0 ] || fail 'a TMPDIR with a quote did not stop the run'
grep -q '^test-engine: TMPDIR contains' "$fx/err" || { cat "$fx/err" >&2; fail 'a TMPDIR with a quote: no message'; }
checks=$((checks + 1))
fixture; mkdir "$work/odd\\dir"
set +e; TMPDIR="$work/odd\\dir" sh "$fx/scripts/test-engine.sh" > "$fx/out" 2> "$fx/err"; status=$?; set -e
[ "$status" -ne 0 ] || fail 'a TMPDIR with a backslash did not stop the run'
grep -q '^test-engine: TMPDIR contains' "$fx/err" || { cat "$fx/err" >&2; fail 'a TMPDIR with a backslash: no message'; }
checks=$((checks + 1))
printf 'test-engine-leaks: %s passed, 0 failed\n' "$checks"
