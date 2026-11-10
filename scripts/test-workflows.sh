#!/bin/sh
# Every checkout step is pinned to a full commit SHA and drops the token after checkout.
# Also runs fixtures: each way around the rule must make the check fail.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate workflows.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# check_dir <dir>: prints the number of checkout steps; messages go to stderr; fails on a violation.
check_dir() {
  found=0
  for file in "$1"/*.yml "$1"/*.yaml; do
    [ -f "$file" ] || continue
    count=$(awk -v name="${file##*/}" '
      function finish() {
        if (open && !persist) { printf "test-workflows: %s:%d checkout lacks persist-credentials: false\n", name, line > "/dev/stderr"; bad = 1 }
        open = 0
      }
      { text = $0; sub(/[ \t]#.*$/, "", text); sub(/^[ \t]*#.*$/, "", text) }
      text ~ /^ *- / { finish() }
      tolower(text) ~ /uses:[ \t]*["'\'']?actions\/checkout/ {
        open = 1; persist = 0; line = NR; n++
        if (text !~ /@[0-9a-f]{40}["'\'']?[ \t]*$/) { printf "test-workflows: %s:%d checkout is not pinned to a 40-hex commit\n", name, NR > "/dev/stderr"; bad = 1 }
      }
      text ~ /persist-credentials:[ \t]*["'\'']?false["'\'']?[ \t]*$/ { persist = 1 }
      END { finish(); print n + 0; exit bad }
    ' "$file") || return 1
    found=$((found + count))
  done
  echo "$found"
}
sha=3d3c42e5aac5ba805825da76410c181273ba90b1
good() { printf 'jobs:\n  a:\n    steps:\n      - uses: actions/checkout@%s # v7\n        with:\n          persist-credentials: false\n' "$sha"; }
checks=0
count=$(check_dir "$root/.github/workflows") || exit 1
[ "$count" -gt 0 ] || { echo 'test-workflows: no checkout steps found' >&2; exit 1; }
checks=$((checks + count))
# Fixtures: the good one passes, every bypass fails.
fixture() { # <name> <accepts: yes|no> <content>
  mkdir -p "$work/$1"
  printf '%s\n' "$3" > "$work/$1/$4"
  if out=$(check_dir "$work/$1" 2>/dev/null); then ok=yes; else ok=no; fi
  [ "$ok" = "$2" ] || { echo "test-workflows: fixture $1: accepted=$ok, expected $2 ($out)" >&2; exit 1; }
  checks=$((checks + 1))
}
fixture good yes "$(good)" w.yml
fixture yaml-extension no "$(good | sed "s|@$sha|@v4|")" w.yaml
fixture tag no "$(good | sed "s|@$sha.*|@v4|")" w.yml
fixture comment-only no "$(good | sed 's|persist-credentials|# persist-credentials|')" w.yml
fixture true no "$(good | sed 's|false|true # false|')" w.yml
fixture quoted no "$(good | sed "s|uses: actions/checkout@$sha # v7|uses: \"actions/checkout@v4\"|")" w.yml
fixture quoted-pinned yes "$(good | sed "s|uses: actions/checkout@$sha # v7|uses: \"actions/checkout@$sha\"|")" w.yml
fixture uppercase no "$(good | sed "s|actions/checkout@$sha|Actions/checkout@v4|")" w.yml
fixture short-sha no "$(good | sed "s|$sha|3d3c42e|")" w.yml
printf 'test-workflows: %s passed, 0 failed\n' "$checks"
