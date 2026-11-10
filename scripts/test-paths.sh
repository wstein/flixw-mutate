#!/bin/sh
# Build and run the plugin from a repository and an SDK checkout whose paths contain spaces.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sdk=$(sh "$root/scripts/check-sdk-source.sh")
revision=$(git -C "$sdk" rev-parse HEAD)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate paths.XXXXXX")
trap 'rm -rf "$work"' EXIT INT TERM
mkdir "$work/my repo"
cp -R "$root/scripts" "$root/src" "$root/sdk-source.properties" "$work/my repo/"
git clone -q --no-checkout "$sdk" "$work/sdk dir"
git -C "$work/sdk dir" checkout -q --detach "$revision"
FLIXW_SDK_SOURCE="$work/sdk dir" FLIXW_MUTATE_DIST="$work/my repo/out dir" \
  sh "$work/my repo/scripts/package.sh" 0.0.0-test > /dev/null
sh "$work/my repo/scripts/check-sdk-package.sh" "$work/my repo/out dir/plugin.jar" > /dev/null
sh "$work/my repo/scripts/test-plugin.sh" "$work/my repo/out dir/plugin.jar" 0.0.0-test > /dev/null
echo 'test-paths: 3 passed, 0 failed'
