#!/bin/sh
# Test of the Datalog hook and of the relations check for a solve without rules (facts only), on datalog-facts-fixture.
# A constraint set without rules is a program solve and is recorded (`forAll` on the empty rule vector is true and must
# not classify it as a query over a model). Cases:
#   facts-only    the fixture with the ground truth: exit 0
#   no-provenance the same solve with -Dflixw.covcfg.noprov=1: edb is n/a and the rule list is empty, which proves nothing
#                 (DatalogGen.edbNotAvailable), so the relations check deviates: exit 1, exactly one deviation
# Usage: SPIKE_WORK=<scratch dir> sh spikes/coverage-generalisation/test-datalog-facts.sh
set -u
umask 022
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
work=${SPIKE_WORK:?set SPIKE_WORK to a scratch directory}
mkdir -p "$work"
pass=0
fail=0
ok() { pass=$((pass + 1)); echo "ok: $1"; }
bad() { fail=$((fail + 1)); echo "FAIL: $1" >&2; }

# check <name> <JAVA_OPTS> <expected exit code> <expected line in the output>
check() {
  n=$1; opts=$2; want=$3; line=$4
  SPIKE_WORK=$work JAVA_OPTS=$opts sh "$here/run-datalog.sh" "$here/datalog-facts-fixture" expect > "$work/facts-$n.out" 2> "$work/facts-$n.err"
  rc=$?
  [ "$rc" = "$want" ] && ok "$n: exit $want" || bad "$n: exit $rc, expected $want"
  grep -qxF -- "$line" "$work/facts-$n.out" && ok "$n: output has '$line'" || bad "$n: output lacks '$line'"
}

check facts-only '' 0 'RESULT: OK'
check no-provenance -Dflixw.covcfg.noprov=1 1 'RESULT: FAIL 1 deviations'
grep -qF -- 'DEVIATION relations TestFacts.testNumbers expected solves 1 Num=3 edb 3, got 1 Num=3, edb -1' "$work/facts-no-provenance.out" \
  && ok 'no-provenance: the deviation is the relations check' || bad 'no-provenance: no relations deviation'
echo "datalog facts: $pass passed, $fail failed"
[ "$fail" = 0 ]
