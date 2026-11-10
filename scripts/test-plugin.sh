#!/bin/sh
# Run the packaged plugin through its shell contract: context, exit codes and report header.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
jar=${1:?usage: sh scripts/test-plugin.sh plugin.jar <version>}
version=${2:?usage: sh scripts/test-plugin.sh plugin.jar <version>}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
revision=$(awk -F= '$1 == "revision" {print $2}' "$root/sdk-source.properties")
java_home=$(dirname "$(dirname "$(command -v java)")")
checks=0
fail() { echo "test-plugin: $*" >&2; cat "$work/out" "$work/err" >&2; exit 1; }
# run <expected-exit> <env...> -- <args...>
run() {
  expected=$1; shift
  set +e
  env -i PATH="$PATH" "$@" > "$work/out" 2> "$work/err"
  actual=$?
  set -e
  [ "$actual" -eq "$expected" ] || fail "expected exit $expected, got $actual: $*"
  checks=$((checks + 1))
}
contains() { grep -qF -- "$2" "$work/$1" || fail "$1 lacks: $2"; checks=$((checks + 1)); }
plugin="java -jar $jar"
full="FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT=$work FLIXW_COMPILER_JAR=$work/flix-0.79.0.jar FLIXW_JAVA_HOME=$java_home FLIXW_COMPILER_VERSION=0.79.0"
# shellcheck disable=SC2086
run 2 $plugin
contains err 'ABI version 1'
# shellcheck disable=SC2086
run 2 FLIXW_ABI_VERSION=2 $plugin
contains err 'ABI version 1'
# shellcheck disable=SC2086
run 2 FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$work" $plugin
contains err 'FLIXW_COMPILER_JAR'
# shellcheck disable=SC2086
run 2 FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$work" FLIXW_COMPILER_JAR="$work/c.jar" FLIXW_JAVA_HOME="$java_home" $plugin
contains err 'FLIXW_COMPILER_VERSION'
# shellcheck disable=SC2086
run 0 $full $plugin
contains out "flixw-mutate plugin=$version sdk=$revision flix=0.79.0"
# The three levels are separate fields: a changed Flix version changes only flix=.
# shellcheck disable=SC2086
run 0 $full FLIXW_COMPILER_VERSION=0.77.0 $plugin
contains out "plugin=$version sdk=$revision flix=0.77.0"
# shellcheck disable=SC2086
run 0 FLIXW_ABI_VERSION=1 $plugin --version
contains out "flixw-mutate plugin=$version sdk=$revision"
# shellcheck disable=SC2086
run 0 FLIXW_ABI_VERSION=1 $plugin --help
contains out 'usage:'
printf 'test-plugin: %s passed, 0 failed\n' "$checks"
