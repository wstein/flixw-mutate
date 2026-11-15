#!/bin/sh
# The plugin test rejects paths that JAVA_TOOL_OPTIONS cannot carry.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate plugin tmpdir.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
checks=0
nl='
'
for path in "$work/double\"quote" "$work/line${nl}break"; do
  mkdir "$path"
  set +e
  TMPDIR=$path sh "$root/scripts/test-plugin.sh" unused.jar test > "$work/out" 2> "$work/err"
  status=$?
  set -e
  [ "$status" -eq 2 ] || { echo "test-plugin-tmpdir: status $status, expected 2" >&2; exit 1; }
  grep -q '^test-plugin: TMPDIR contains a double quote or a newline:' "$work/err" || {
    cat "$work/err" >&2; echo 'test-plugin-tmpdir: guard message missing' >&2; exit 1;
  }
  [ -z "$(ls -A "$path")" ] || { echo 'test-plugin-tmpdir: temporary directory left behind' >&2; exit 1; }
  checks=$((checks + 1))
done
printf 'test-plugin-tmpdir: %s passed, 0 failed\n' "$checks"
