#!/bin/sh
# Run the packaged plugin through its shell contract: context, exit codes and report header.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
jar=${1:?usage: sh scripts/test-plugin.sh plugin.jar <version>}
version=${2:?usage: sh scripts/test-plugin.sh plugin.jar <version>}
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate plugin.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
revision=$(awk -F= '$1 == "revision" {print $2}' "$root/sdk-source.properties")
java_home=$(dirname "$(dirname "$(command -v java)")")
checks=0
fail() { echo "test-plugin: $*" >&2; cat "$work/out" "$work/err" >&2; exit 1; }
# run <expected-exit> "<plugin args, no spaces>" [VAR=value ...]: the plugin sees only those variables.
run() {
  expected=$1; plugin_args=$2; shift 2
  set +e
  # shellcheck disable=SC2086
  env -i PATH="$PATH" "$@" java -jar "$jar" $plugin_args > "$work/out" 2> "$work/err"
  actual=$?
  set -e
  [ "$actual" -eq "$expected" ] || fail "expected exit $expected for '$plugin_args', got $actual"
  checks=$((checks + 1))
}
# full <expected-exit> "<plugin args>" [VAR=value ...]: a complete ABI 1 context; later values win.
full() {
  expected=$1; plugin_args=$2; shift 2
  run "$expected" "$plugin_args" FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$work" \
    FLIXW_COMPILER_JAR="$work/flix-0.79.0.jar" FLIXW_JAVA_HOME="$java_home" \
    FLIXW_COMPILER_VERSION=0.79.0 "$@"
}
contains() { grep -qF -- "$2" "$work/$1" || fail "$1 lacks: $2"; checks=$((checks + 1)); }
run 2 ''
contains err 'ABI version 1'
run 2 '' FLIXW_ABI_VERSION=2
contains err 'ABI version 1'
run 2 '' FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$work"
contains err 'FLIXW_COMPILER_JAR'
contains err 'no lock'
run 2 '' FLIXW_ABI_VERSION=1 FLIXW_PROJECT_ROOT="$work" FLIXW_COMPILER_JAR="$work/c.jar" FLIXW_JAVA_HOME="$java_home"
contains err 'FLIXW_COMPILER_VERSION'
full 2 '' FLIXW_COMPILER_VERSION=
contains err 'FLIXW_COMPILER_VERSION'
full 2 '' FLIXW_COMPILER_VERSION='  '
contains err 'FLIXW_COMPILER_VERSION'
full 0 ''
contains out "flixw-mutate plugin=$version sdk=$revision flix=0.79.0"
# The three levels are separate fields: a changed Flix version changes only flix=.
full 0 '' FLIXW_COMPILER_VERSION=0.77.0
contains out "plugin=$version sdk=$revision flix=0.77.0"
# Unknown arguments are a shell error (exit 2), not a silent success.
full 2 '--bogus'
contains err 'unknown argument: --bogus'
full 2 'one two'
contains err 'unknown argument: one'
run 0 --version FLIXW_ABI_VERSION=1
contains out "flixw-mutate plugin=$version sdk=$revision"
run 0 --help FLIXW_ABI_VERSION=1
contains out 'usage: ./flixw mutate [--help | --version]'
printf 'test-plugin: %s passed, 0 failed\n' "$checks"
