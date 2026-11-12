#!/bin/sh
# Test of the marker audit (flixw-mutate#29): the bytecode of the instrumented program is scanned for the inserted markers
# and every missing marker is classified (dead function, or live function = failure). Runs run.sh in audit-only mode
# (-Dcov.auditOnly=true: compile, scan, no test run) on five projects:
#   audit-live  a live function whose branch the compiler removes: exit 1, one LIVE row
#   audit-dead  functions no test reaches, one of them without an entry region: exit 0, only dead rows
#   audit-reach three LIVE rows the audit must not take for dead: an instance def (class name with a numeric suffix), a
#               dropped (@Inline) def applied by a live def, a dropped def applied only by a test: exit 1
#   audit-allowed the audit-live case with an allow list (file, kind, line, reason): exit 0, the row is ALLOWED
#   variants of audit-allowed: a row without a reason (exit 2), a row with another line, kind or file (exit 1, LIVE)
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

# variant <name> <allow-list line (4 tab-separated fields)> <expected exit code> <expected line in the output or the error log>
# audit-allowed with another allow list: only the exact file, kind and line allow the row, and a row needs a reason.
variant() {
  n=$1; row=$2; want=$3; line=$4
  rm -rf "$work/variant-$n"; mkdir -p "$work/variant-$n"
  cp -R "$here/audit-allowed/src" "$here/audit-allowed/test" "$work/variant-$n/"
  printf 'file\tkind\tline\treason\n%s\n' "$row" > "$work/variant-$n/marker-audit-allow.tsv"
  SPIKE_WORK=$work JAVA_OPTS="-Dcov.auditOnly=true -Dcov.plain=false" sh "$here/run.sh" "$work/variant-$n" > "$work/variant-$n.out" 2> "$work/variant-$n.err"
  rc=$?
  [ "$rc" = "$want" ] && ok "variant $n: exit $want" || bad "variant $n: exit $rc, expected $want"
  cat "$work/variant-$n.out" "$work/stderr-variant-$n.log" 2>/dev/null | grep -qF -- "$line" && ok "variant $n: has '$line'" || bad "variant $n: lacks '$line'"
}

check audit-live 1 'MISSING MARKER in a live function: ELSE src/Fold.flix:5 in Fold.lit'
check audit-dead 0 'inside a dead function 9, allowed 0, LIVE function 0'
check audit-reach 1 'MISSING MARKER in a live function: ELSE src/Reach.flix:28 in Direct.only'
check audit-allowed 0 'allowed 1, LIVE function 0'
variant empty-reason "$(printf 'src/Fold.flix\tELSE\t5\t')" 2 'expected file, kind, line and a reason'
variant wrong-line "$(printf 'src/Fold.flix\tELSE\t6\tx')" 1 'MISSING MARKER in a live function: ELSE src/Fold.flix:5 in Fold.lit'
variant wrong-kind "$(printf 'src/Fold.flix\tTHEN\t5\tx')" 1 'MISSING MARKER in a live function: ELSE src/Fold.flix:5 in Fold.lit'
variant wrong-file "$(printf 'src/Other.flix\tELSE\t5\tx')" 1 'MISSING MARKER in a live function: ELSE src/Fold.flix:5 in Fold.lit'
check fixture 0 'LIVE function 0'
echo "marker audit: $pass passed, $fail failed"
[ "$fail" = 0 ]
