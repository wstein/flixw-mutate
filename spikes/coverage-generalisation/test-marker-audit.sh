#!/bin/sh
# Test of the marker audit (flixw-mutate#29): the bytecode of the instrumented program is scanned for the inserted markers
# and every missing marker is classified (dead function, or live function = failure). Runs run.sh in audit-only mode
# (-Dcov.auditOnly=true: compile, scan, no test run) on three projects:
#   audit-live  a live function whose branch the compiler removes: exit 1, one LIVE row
#   audit-dead  functions no test reaches, one of them without an entry region: exit 0, only dead rows
#   fixture     the main fixture: exit 0, the entry of Grade.unused is the only missing marker and it is dead
# Each project has an expected-missing.tsv (the audit's own marker-audit.tsv, compared as a whole).
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-generalisation/test-marker-audit.sh
set -u
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok: $1"; }
bad() { fail=$((fail + 1)); echo "FAIL: $1" >&2; }

# check <project> <expected exit code> <expected line in the output>
check() {
  p=$1; want=$2; line=$3
  SPIKE_WORK=$work JAVA_OPTS="-Dcov.auditOnly=true -Dcov.plain=false" sh "$here/run.sh" "$here/$p" > "$work/audit-$p.out" 2> "$work/audit-$p.err"
  rc=$?
  [ "$rc" = "$want" ] && ok "$p: exit $want" || bad "$p: exit $rc, expected $want"
  grep -qF -- "$line" "$work/audit-$p.out" && ok "$p: output has '$line'" || bad "$p: output lacks '$line'"
  if [ -f "$work/run-$p/marker-audit.tsv" ] && cmp -s "$work/run-$p/marker-audit.tsv" "$here/$p/expected-missing.tsv"; then
    ok "$p: marker-audit.tsv equals expected-missing.tsv"
  else
    bad "$p: marker-audit.tsv differs from expected-missing.tsv"
    diff "$work/run-$p/marker-audit.tsv" "$here/$p/expected-missing.tsv" >&2 || true
  fi
}

check audit-live 1 'MISSING MARKER in a live function: ELSE src/Fold.flix:5 in Fold.lit'
check audit-dead 0 'LIVE function 0'
check fixture 0 'LIVE function 0'
echo "marker audit: $pass passed, $fail failed"
[ "$fail" = 0 ]
