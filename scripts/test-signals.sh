#!/bin/sh
# A SIGTERM must stop each test script (exit 143) and remove its temporary directory.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate signals.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$work/bin" "$work/tmp"
# A java that takes a moment lets the signal arrive while the script is running.
printf '#!/bin/sh\nsleep 2\nexit 0\n' > "$work/bin/java"
chmod +x "$work/bin/java"
checks=0
# <script> <args...>: start it, send SIGTERM, expect 143 and an empty TMPDIR.
check() {
  TMPDIR="$work/tmp" PATH="$work/bin:$PATH" sh "$@" > /dev/null 2>&1 &
  pid=$!
  sleep 1
  kill -TERM "$pid"
  set +e; wait "$pid"; status=$?; set -e
  [ "$status" -eq 143 ] || { echo "test-signals: $1 exited $status after SIGTERM, expected 143" >&2; exit 1; }
  [ -z "$(ls -A "$work/tmp")" ] || { echo "test-signals: $1 left a temporary directory" >&2; exit 1; }
  checks=$((checks + 1))
}
check "$root/scripts/test-plugin.sh" "$root/dist-unused.jar" 0.0.0-test
# The other scripts use the same trap lines; no trap may be left without an exit on a signal.
for script in "$root"/scripts/test*.sh "$root/scripts/package.sh"; do
  [ "${script##*/}" != test-signals.sh ] || continue
  if grep -q "^trap .*INT" "$script" && grep -q 'INT TERM' "$script"; then
    echo "test-signals: ${script##*/} traps INT and TERM without exiting" >&2; exit 1
  fi
  grep -q "^trap 'exit 143' TERM" "$script" || {
    echo "test-signals: ${script##*/} has no TERM trap that exits" >&2; exit 1; }
  checks=$((checks + 1))
done
printf 'test-signals: %s passed, 0 failed\n' "$checks"
