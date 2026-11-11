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
contains out 'usage: ./flixw mutate [--timeout-ms <ms>] [--fail-on-survived] | --help | --version'
# Options.
full 2 '--timeout-ms'
contains err 'usage'
full 2 '--timeout-ms abc'
contains err '--timeout-ms'
full 2 '--timeout-ms 0'
contains err '--timeout-ms'
full 2 '--fail-on-survived --fail-on-survived'
contains err 'unknown argument'
# An empty project: nothing to mutate, a report with zero mutants.
full 0 ''
contains out 'mutants: 0 (killed 0, survived 0, timeout 0)'
contains out 'mutation score: n/a (no mutants)'
full 0 '--fail-on-survived'
# A project with sources and a missing compiler jar is a shell error, not a crash.
mkdir -p "$work/proj/src"
printf 'mod A { pub def f(x: Int32): Int32 = x + 1 }\n' > "$work/proj/src/A.flix"
full 2 '' FLIXW_PROJECT_ROOT="$work/proj"
contains err 'flixw-mutate:'
# The planted fixture through the packaged plugin (needs the pinned compiler jar; about 30 s).
# FLIXW_TEST_ENGINE_RUN=0 skips it: test-paths.sh runs it for two of its path variants only.
if [ "${FLIXW_TEST_ENGINE_RUN:-1}" = 1 ]; then
flix_jar=$(sh "$root/spikes/common/fetch-flix.sh" "${FLIXW_TEST_JARS:-$work/jars}")
# The project is copied: the engine refuses a tab or line break in a project path, the repository path may have one.
cp -R "$root/src/test/fixtures/calc" "$work/calc"
full 0 '' FLIXW_PROJECT_ROOT="$work/calc" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutants: 31 (killed 21, survived 9, timeout 1); sites left out: 0'
contains out 'survived  src/Calc.flix:4:13  < -> <='
contains out 'timeout   src/Calc.flix:11:39  - -> +'
full 1 '--fail-on-survived' FLIXW_PROJECT_ROOT="$work/calc" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutation score: 71.0%'
fi
printf 'test-plugin: %s passed, 0 failed\n' "$checks"
