#!/bin/sh
# Source checks: the SDK pin boundary, then the packaged plugin. Needs FLIXW_SDK_SOURCE (see README).
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sh "$root/scripts/test-sdk-source.sh"
# Packaging must run the pin guard: without a pinned checkout it builds nothing.
if env -u FLIXW_SDK_SOURCE sh "$root/scripts/package.sh" 0.0.0-test >/dev/null 2>"$root/dist-guard.err"; then
  rm -f "$root/dist-guard.err"; echo 'test: package.sh built without FLIXW_SDK_SOURCE' >&2; exit 1
fi
grep -q FLIXW_SDK_SOURCE "$root/dist-guard.err" || { cat "$root/dist-guard.err" >&2; rm -f "$root/dist-guard.err"; exit 1; }
rm -f "$root/dist-guard.err"
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
sh "$root/scripts/check-sdk-package.sh" "$root/dist/plugin.jar"
sh "$root/scripts/test-plugin.sh" "$root/dist/plugin.jar" 0.0.0-test
first=$(shasum -a 256 "$root/dist/plugin.jar" | awk '{print $1}')
sleep 3
sh "$root/scripts/package.sh" 0.0.0-test >/dev/null
second=$(shasum -a 256 "$root/dist/plugin.jar" | awk '{print $1}')
[ "$first" = "$second" ] || { echo 'test: plugin.jar is not reproducible' >&2; exit 1; }
echo 'test: reproducible plugin.jar'
