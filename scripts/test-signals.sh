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
# Every script that sets a trap must exit on INT (130) and TERM (143) instead of running on.
for script in "$root"/scripts/*.sh; do
  name=${script##*/}
  [ "$name" != test-signals.sh ] || continue
  grep -q '^trap ' "$script" || continue
  if grep '^trap ' "$script" | grep -v "^trap 'exit 130' INT\$" | grep -v "^trap 'exit 143' TERM\$" | grep -qE ' (INT|TERM)'; then
    echo "test-signals: $name lists INT in a cleanup trap without exiting" >&2; exit 1
  fi
  grep -q "^trap 'exit 130' INT\$" "$script" || { echo "test-signals: $name has no INT trap that exits 130" >&2; exit 1; }
  grep -q "^trap 'exit 143' TERM\$" "$script" || { echo "test-signals: $name has no TERM trap that exits 143" >&2; exit 1; }
  checks=$((checks + 1))
done
printf 'test-signals: %s passed, 0 failed\n' "$checks"
