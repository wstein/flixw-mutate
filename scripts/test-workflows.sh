#!/bin/sh
# Every checkout step is pinned to a full commit SHA and drops the token after checkout.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
checks=0
for file in "$root"/.github/workflows/*.yml; do
  name=${file##*/}
  count=$(awk -v name="$name" '
    function finish() {
      if (open && !persist) { printf "test-workflows: %s:%d checkout lacks persist-credentials: false\n", name, line > "/dev/stderr"; bad = 1 }
      open = 0
    }
    /^ *- / { finish() }
    /uses: *actions\/checkout/ {
      open = 1; persist = 0; line = NR; n++
      if ($0 !~ /actions\/checkout@[0-9a-f]{40}( |$)/) { printf "test-workflows: %s:%d checkout is not pinned to a 40-hex commit\n", name, NR > "/dev/stderr"; bad = 1 }
    }
    /persist-credentials: *false/ { persist = 1 }
    END { finish(); print n + 0 > "/dev/stdout"; exit bad }
  ' "$file") || exit 1
  checks=$((checks + count))
done
[ "$checks" -gt 0 ] || { echo 'test-workflows: no checkout steps found' >&2; exit 1; }
printf 'test-workflows: %s checkout steps passed, 0 failed\n' "$checks"
