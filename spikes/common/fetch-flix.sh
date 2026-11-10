#!/bin/sh
# Fetch the pinned Flix compiler jar and verify its SHA-256. The jar is never committed.
#
# Usage: sh spikes/common/fetch-flix.sh <target-dir>
# Environment (optional):
#   FLIXW_JAR_CACHE   directory with flix-0.79.0.jar; used (read only) if its digest matches
#   FLIXW_FLIX_BASE_URL   default https://github.com/flix/flix/releases/download
# Prints the path of the verified jar on stdout. Exit 1 on digest mismatch or download failure.
#
# Pin: version and digest are the 0.79.0 entry of flixw-plugin-sdk versions.json
# (flix-bridge/resources/dev/flixw/sdk/bridge/versions.json).
set -eu
umask 022
version=0.79.0
expected=8fad2aa09567a7edabcc43f4101fa6199131df2c22929e001c87659d764f6dcf
[ "$#" -eq 1 ] || { echo "usage: fetch-flix.sh <target-dir>" >&2; exit 2; }
dir=$1
base=${FLIXW_FLIX_BASE_URL:-https://github.com/flix/flix/releases/download}
if command -v sha256sum >/dev/null 2>&1; then sum="sha256sum"; else sum="shasum -a 256"; fi
digest() { $sum "$1" | cut -d' ' -f1; }
mkdir -p "$dir"
jar=$dir/flix-$version.jar
if [ -f "$jar" ] && [ "$(digest "$jar")" = "$expected" ]; then echo "$jar"; exit 0; fi
rm -f "$jar" "$jar.part"
cache=${FLIXW_JAR_CACHE:-}
if [ -n "$cache" ] && [ -f "$cache/flix-$version.jar" ] && [ "$(digest "$cache/flix-$version.jar")" = "$expected" ]; then
  cp "$cache/flix-$version.jar" "$jar.part"
else
  curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 30 --max-time 600 -o "$jar.part" "$base/v$version/flix.jar" \
    || { rm -f "$jar.part"; echo "fetch-flix: cannot download Flix $version" >&2; exit 1; }
fi
got=$(digest "$jar.part")
if [ "$got" != "$expected" ]; then
  rm -f "$jar.part"
  echo "fetch-flix: digest mismatch for Flix $version (expected $expected, actual $got)" >&2
  exit 1
fi
mv "$jar.part" "$jar"
echo "$jar"
