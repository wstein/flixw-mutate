#!/bin/sh
# Source checks: the SDK pin boundary, then the packaged plugin. Needs FLIXW_SDK_SOURCE (see README).
# Builds go to a temporary directory, never over dist/.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate test.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# One compiler jar for the engine and plugin tests (digest-checked, cached for this run).
FLIXW_TEST_JARS=$work/jars
export FLIXW_TEST_JARS
sh "$root/scripts/test-workflows.sh"
sh "$root/scripts/test-signals.sh"
sh "$root/scripts/test-engine.sh"
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
# A pinned SDK that ships META-INF/LICENSE itself must not be overwritten by this repository's LICENSE: a copy of
# the packaging script in a temporary tree with a fixture SDK and its own pin (the real pin names a real commit).
fx=$work/license-fixture
mkdir -p "$fx/repo/scripts" "$fx/repo/src/main/java" "$fx/sdk/kit/src" "$fx/sdk/kit/resources/META-INF"
cp "$root/scripts/package.sh" "$root/scripts/check-sdk-source.sh" "$fx/repo/scripts/"
printf 'repository content license\n' > "$fx/repo/LICENSE"
printf 'public final class Main { public static void main(String[] a) { } }\n' > "$fx/repo/src/main/java/Main.java"
printf 'public final class Kit { }\n' > "$fx/sdk/kit/src/Kit.java"
printf 'license of the SDK pin\n' > "$fx/sdk/kit/resources/META-INF/LICENSE"
git -C "$fx/sdk" init -q
git -C "$fx/sdk" config user.email 'mutate@example.invalid'
git -C "$fx/sdk" config user.name 'Mutate Test'
git -C "$fx/sdk" add .
git -C "$fx/sdk" commit -qm 'test: SDK fixture with its own META-INF/LICENSE'
printf 'repository=https://github.com/wstein/flixw-plugin-sdk\nrevision=%s\nmodule=kit\n' "$(git -C "$fx/sdk" rev-parse HEAD)" > "$fx/repo/sdk-source.properties"
if FLIXW_SDK_SOURCE="$fx/sdk" FLIXW_MUTATE_DIST="$fx/dist-clash" sh "$fx/repo/scripts/package.sh" 0.0.0-test > "$fx/out" 2> "$fx/err"; then
  echo 'test: package.sh overwrote META-INF/LICENSE of the SDK pin' >&2; exit 1
fi
grep -q '^package: the SDK pin ships META-INF/LICENSE' "$fx/err" || { cat "$fx/err" >&2; echo 'test: package.sh did not fail at the META-INF/LICENSE clash' >&2; exit 1; }
[ ! -e "$fx/dist-clash/plugin.jar" ] || { echo 'test: package.sh left a jar after the LICENSE clash' >&2; exit 1; }
# Control: the same fixture without that file builds, so the failure above is the clash and nothing else.
git -C "$fx/sdk" rm -q kit/resources/META-INF/LICENSE
mkdir -p "$fx/sdk/kit/resources"
printf 'x\n' > "$fx/sdk/kit/resources/other.txt"
git -C "$fx/sdk" add .
git -C "$fx/sdk" commit -qm 'test: SDK fixture without META-INF/LICENSE'
printf 'repository=https://github.com/wstein/flixw-plugin-sdk\nrevision=%s\nmodule=kit\n' "$(git -C "$fx/sdk" rev-parse HEAD)" > "$fx/repo/sdk-source.properties"
FLIXW_SDK_SOURCE="$fx/sdk" FLIXW_MUTATE_DIST="$fx/dist-ok" sh "$fx/repo/scripts/package.sh" 0.0.0-test > "$fx/out" 2> "$fx/err" || { cat "$fx/err" >&2; echo 'test: package.sh failed on the fixture without a clash' >&2; exit 1; }
unzip -p "$fx/dist-ok/plugin.jar" META-INF/LICENSE | cmp -s - "$fx/repo/LICENSE" || { echo 'test: fixture jar lacks the repository LICENSE' >&2; exit 1; }
echo 'test: package.sh does not overwrite a META-INF/LICENSE of the SDK pin'
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
