#!/bin/sh
# Source checks: the SDK pin boundary, then the packaged plugin. Needs FLIXW_SDK_SOURCE (see README).
# Builds go to a temporary directory, never over dist/.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate test.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
sh "$root/scripts/test-workflows.sh"
sh "$root/scripts/test-sdk-source.sh"
# Packaging must run the pin guard: a checkout at another revision builds nothing.
mkdir -p "$work/wrong/kit/src" "$work/wrong/kit/resources"
git -C "$work/wrong" init -q
git -C "$work/wrong" config user.email 'mutate@example.invalid'
git -C "$work/wrong" config user.name 'Mutate Test'
printf 'x\n' > "$work/wrong/kit/src/X.java"
git -C "$work/wrong" add .
git -C "$work/wrong" commit -qm 'test: SDK fixture at another revision'
if FLIXW_SDK_SOURCE="$work/wrong" FLIXW_MUTATE_DIST="$work/wrong-dist" \
    sh "$root/scripts/package.sh" 0.0.0-test > "$work/out" 2> "$work/err"; then
  echo 'test: package.sh built from an SDK checkout at another revision' >&2; exit 1
fi
grep -q '^sdk-source: SDK HEAD is' "$work/err" || { cat "$work/err" >&2; echo 'test: package.sh did not fail in the pin guard' >&2; exit 1; }
[ ! -e "$work/wrong-dist/plugin.jar" ] || { echo 'test: package.sh left a jar after the guard failed' >&2; exit 1; }
echo 'test: package.sh refuses an unpinned SDK checkout'
export FLIXW_MUTATE_DIST="$work/dist"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
sh "$root/scripts/check-sdk-package.sh" "$work/dist/plugin.jar"
sh "$root/scripts/test-plugin.sh" "$work/dist/plugin.jar" 0.0.0-test
sh "$root/scripts/test-paths.sh"
first=$(shasum -a 256 "$work/dist/plugin.jar" | awk '{print $1}')
sleep 3
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
second=$(shasum -a 256 "$work/dist/plugin.jar" | awk '{print $1}')
[ "$first" = "$second" ] || { echo 'test: plugin.jar is not reproducible' >&2; exit 1; }
echo 'test: reproducible plugin.jar'
