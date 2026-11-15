#!/bin/sh
# Contract checks for version selection and the explicit legacy fixture suite.
set -eu
umask 022
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate versions.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
fail() { echo "test-flix-versions: $*" >&2; exit 1; }
mkdir -p "$work/bin"
cat > "$work/bin/curl" <<'CURL'
#!/bin/sh
while [ "$1" != -o ]; do shift; done
printf 'wrong jar\n' > "$2"
CURL
chmod +x "$work/bin/curl"
for version in 0.79.0 0.80.0; do
  if PATH="$work/bin:$PATH" FLIXW_JAR_CACHE= FLIXW_FLIX_VERSION=$version sh "$root/spikes/common/fetch-flix.sh" "$work/jars" > "$work/out" 2> "$work/err"; then fail "$version accepted a wrong digest"; fi
  grep -q "digest mismatch for Flix $version" "$work/err" || fail "$version did not reject the digest"
  [ ! -e "$work/jars/flix-$version.jar" ] && [ ! -e "$work/jars/flix-$version.jar.part" ] || fail "$version left an unverified jar"
done
if PATH="$work/bin:$PATH" FLIXW_JAR_CACHE= FLIXW_FLIX_VERSION= sh "$root/spikes/common/fetch-flix.sh" "$work/default" > "$work/out" 2> "$work/err"; then fail 'default accepted a wrong digest'; fi
grep -q 'digest mismatch for Flix 0.80.0' "$work/err" || fail 'wrong default version'
if FLIXW_FLIX_VERSION=0.0.0 sh "$root/spikes/common/fetch-flix.sh" "$work/unknown" > "$work/out" 2> "$work/err"; then fail 'unknown version accepted'; fi
grep -q 'unknown Flix version' "$work/err" || fail 'unknown version not reported'
[ ! -e "$work/unknown" ] || fail 'unknown version created a target directory'
fx=$work/fixture
mkdir -p "$fx/scripts" "$fx/spikes/common" "$fx/src/main/java/dev/flixw/mutate" "$fx/src/test/java/dev/flixw/mutate"
cp "$root/scripts/test-engine.sh" "$fx/scripts/"
printf '#!/bin/sh\nmkdir -p "$1"\ntouch "$1/flix.jar"\necho "$1/flix.jar"\n' > "$fx/spikes/common/fetch-flix.sh"
for suite in SchemataCompileTest SchemataTest EngineTest DatalogHitsTest DatalogEdgeTest DatalogMutantsTest OrderTest ExtraTest; do
  printf 'package dev.flixw.mutate; public class %s { public static void main(String[] a) { System.out.println("%s: 1 passed, 0 failed"); } }\n' "$suite" "$suite" > "$fx/src/test/java/dev/flixw/mutate/$suite.java"
done
sh "$fx/scripts/test-engine.sh" --fixtures > "$work/out" 2> "$work/err" || fail 'fixture selection failed'
for suite in SchemataCompileTest SchemataTest EngineTest DatalogHitsTest DatalogEdgeTest DatalogMutantsTest OrderTest; do
  grep -q "^$suite: 1 passed, 0 failed$" "$work/out" || fail "$suite omitted"
done
if grep -q '^ExtraTest:' "$work/out"; then fail 'fixture selection included ExtraTest'; fi
grep -q '^test-engine: 7 passed, 0 failed$' "$work/out" || fail 'fixture total is wrong'
if sh "$fx/scripts/test-engine.sh" --bogus > "$work/out" 2> "$work/err"; then fail 'unknown engine argument accepted'; fi
echo 'test-flix-versions: 22 passed, 0 failed'
