#!/bin/sh
# Build a self-contained executable JAR using only the JDK.
#
# The plugin embeds the SDK kit from the exact source revision in sdk-source.properties.
# FLIXW_SDK_SOURCE names a clean checkout of that revision; check-sdk-source.sh refuses
# anything else, and there is no download or sibling-checkout fallback.
set -eu

version=${1:?usage: sh scripts/package.sh <version>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sdk=$(sh "$root/scripts/check-sdk-source.sh")
dist=${FLIXW_MUTATE_DIST:-$root/dist}
work=$dist/work
classes=$work/classes
sources=$work/sources.txt
entries=$work/entries.txt

rm -rf "$work"
mkdir -p "$classes"
# javac and jar read an @argfile with whitespace separation unless a path is quoted, so every
# path is written as "..." with backslash and quote escaped (paths with a newline are unsupported).
quote() { sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/^/"/' -e 's/$/"/'; }
{ find "$sdk/kit/src" "$root/src/main/java" -name '*.java' -print; } | LC_ALL=C sort | quote > "$sources"
javac -Xlint:all -Werror --release 21 -d "$classes" @"$sources"
# Only kit/resources enters the artifact; the pin is embedded so the report header can name it.
mkdir -p "$classes/dev/flixw/plugin"
cp -R "$sdk/kit/resources/." "$classes/"
cp "$root/sdk-source.properties" "$classes/dev/flixw/plugin/sdk-source.properties"
printf 'Main-Class: dev.flixw.plugin.PluginMain\nImplementation-Version: %s\n' "$version" \
  > "$work/MANIFEST.MF"
# Entry order and timestamps change a JAR's digest; fix both so one source and version give one digest.
(cd "$classes" && find . -type f -print | LC_ALL=C sort | quote > "$entries")
(cd "$classes" && jar --create --file "$dist/plugin.jar" --manifest "$work/MANIFEST.MF" \
  --date=1980-01-01T00:00:02Z @"$entries")
rm -rf "$work"
(cd "$dist" && shasum -a 256 plugin.jar > SHA256SUMS)
printf 'built %s/plugin.jar\n' "$dist"
