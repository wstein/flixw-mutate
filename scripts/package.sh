#!/bin/sh
# Build a self-contained executable JAR using only the JDK.
set -eu

version=${1:?usage: sh scripts/package.sh <version>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
dist=$root/dist
classes=$dist/classes

rm -rf "$classes"
mkdir -p "$classes"
javac --release 21 -d "$classes" "$root/src/main/java/dev/flixw/plugin/PluginMain.java"
printf 'Main-Class: dev.flixw.plugin.PluginMain\nImplementation-Version: %s\n' "$version" \
  > "$dist/MANIFEST.MF"
jar --create --file "$dist/plugin.jar" --manifest "$dist/MANIFEST.MF" -C "$classes" .
(cd "$dist" && shasum -a 256 plugin.jar > SHA256SUMS)
printf 'built %s/plugin.jar\n' "$dist"
