#!/bin/sh
# Build and run the plugin from repository, SDK and output paths with special characters,
# relative output paths, and check that a failed build leaves no work directory behind.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
sdk=$(sh "$root/scripts/check-sdk-source.sh")
revision=$(git -C "$sdk" rev-parse HEAD)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate paths.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
checks=0
tab=$(printf '\t')
nl='
'
# build_and_run <label> <repo dir> <sdk dir> <dist dir> [relative]: dist is relative to $work when "relative" is given.
build_and_run() {
  mkdir -p "$work/$1"
  repo=$work/$1/$2
  mkdir -p "$repo"
  cp -R "$root/scripts" "$root/src" "$root/sdk-source.properties" "$repo/"
  git clone -q --no-checkout "$sdk" "$work/$1/$3"
  git -C "$work/$1/$3" checkout -q --detach "$revision"
  if [ "${5:-}" = relative ]; then
    (cd "$work" && FLIXW_SDK_SOURCE="$work/$1/$3" FLIXW_MUTATE_DIST="$1/$4" \
      sh "$repo/scripts/package.sh" 0.0.0-test > /dev/null)
    jar=$work/$1/$4/plugin.jar
  else
    FLIXW_SDK_SOURCE="$work/$1/$3" FLIXW_MUTATE_DIST="$work/$1/$4" \
      sh "$repo/scripts/package.sh" 0.0.0-test > /dev/null
    jar=$work/$1/$4/plugin.jar
  fi
  [ ! -e "$work/$1/$4/work" ] || { echo "test-paths: $1: work directory left behind" >&2; exit 1; }
  sh "$repo/scripts/check-sdk-package.sh" "$jar" > /dev/null
  sh "$repo/scripts/test-plugin.sh" "$jar" 0.0.0-test > /dev/null
  checks=$((checks + 1))
}
build_and_run space 'my repo' 'sdk dir' 'out dir'
build_and_run dquote 'my"repo' 'sdk"dir' 'out"dir'
build_and_run backslash 'my\repo' 'sdk\dir' 'out\dir'
build_and_run squote "my'repo" "sdk'dir" "out'dir"
build_and_run dollar 'my$repo' 'sdk$dir' 'out$dir'
build_and_run tab "my${tab}repo" "sdk${tab}dir" "out${tab}dir"
build_and_run relative 'my repo' 'sdk dir' 'rel dir/out' relative
# A failing jar step must not leave work/ in the output directory.
mkdir -p "$work/fake"
printf '#!/bin/sh\nexit 1\n' > "$work/fake/jar"
chmod +x "$work/fake/jar"
if PATH="$work/fake:$PATH" FLIXW_SDK_SOURCE="$sdk" FLIXW_MUTATE_DIST="$work/failed out" \
    sh "$root/scripts/package.sh" 0.0.0-test > /dev/null 2>&1; then
  echo 'test-paths: package.sh succeeded with a failing jar' >&2; exit 1
fi
[ ! -e "$work/failed out/work" ] || { echo 'test-paths: failed build left work/' >&2; exit 1; }
checks=$((checks + 1))
# A newline in a path is refused with a message, before anything is created.
if FLIXW_SDK_SOURCE="$sdk" FLIXW_MUTATE_DIST="$work/new${nl}line" \
    sh "$root/scripts/package.sh" 0.0.0-test > /dev/null 2> "$work/err"; then
  echo 'test-paths: accepted a newline in the output path' >&2; exit 1
fi
grep -q 'newline' "$work/err" || { cat "$work/err" >&2; exit 1; }
[ ! -e "$work/new${nl}line" ] || { echo 'test-paths: created a directory for a refused path' >&2; exit 1; }
checks=$((checks + 1))
printf 'test-paths: %s passed, 0 failed\n' "$checks"
