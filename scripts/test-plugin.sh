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
contains out 'usage: ./flixw mutate [--timeout-ms <ms>] [--no-selection] [--no-order] [--fail-on-survived] | --help | --version'
# Options.
full 2 '--timeout-ms'
contains err 'usage'
full 2 '--timeout-ms abc'
contains err '--timeout-ms'
full 2 '--timeout-ms 0'
contains err '--timeout-ms'
full 2 '--fail-on-survived --fail-on-survived'
contains err '--fail-on-survived given twice'
full 2 '--no-selection --no-selection'
contains err '--no-selection given twice'
full 2 '--no-order --no-order'
contains err '--no-order given twice'
full 2 '--timeout-ms 5 --timeout-ms 6'
contains err '--timeout-ms given twice'
# An empty project: nothing to mutate, a report with zero mutants.
full 0 ''
contains out 'mutants: 0 (killed 0, survived 0, not covered 0, timeout 0)'
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
contains out 'mutants: 34 (killed 23, survived 9, not covered 1, timeout 1); sites left out: 0'
contains out 'survived    src/Calc.flix:4:13  < -> <='
contains out 'not covered src/Calc.flix:45:28  == -> !='
contains out 'timeout     src/Calc.flix:11:39  - -> +'
contains out 'test selection: on; tests per mutant:'
full 1 '--fail-on-survived' FLIXW_PROJECT_ROOT="$work/calc" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutation score: 70.6%'
# Without selection every mutant runs every test: the survivors are the 10 of the full run.
full 0 '--no-selection' FLIXW_PROJECT_ROOT="$work/calc" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutants: 34 (killed 23, survived 10, not covered 0, timeout 1); sites left out: 0'
contains out 'test selection: off'
# Without the order of the selected tests the outcomes are the same on this fixture.
full 0 '--no-order' FLIXW_PROJECT_ROOT="$work/calc" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutants: 34 (killed 23, survived 9, not covered 1, timeout 1); sites left out: 0'
# The order of the selected tests: two slow tests that cannot kill and a fast one that does (order fixture). With the
# order the fast test ends the mutant; with --no-order the two slow tests run first.
cp -R "$root/src/test/fixtures/order" "$work/order"
full 0 '' FLIXW_PROJECT_ROOT="$work/order" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'test executions: 1 (a full run: at most 3, computed)'
full 0 '--no-order' FLIXW_PROJECT_ROOT="$work/order" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'test executions: 3 (a full run: at most 3, computed)'
# A project whose only unkilled mutant is `not covered` (g is never called): --fail-on-survived must still exit 1.
mkdir -p "$work/uncovered/src" "$work/uncovered/test"
printf 'mod A {\n    pub def f(x: Int32): Int32 = x + 1\n    pub def g(x: Int32): Int32 = x * 2\n}\n' > "$work/uncovered/src/A.flix"
printf 'mod TestA { @Test def t(): Unit \\ Assert = Assert.assertEq(expected = 2, A.f(1)) }\n' > "$work/uncovered/test/TestA.flix"
full 0 '' FLIXW_PROJECT_ROOT="$work/uncovered" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'mutants: 2 (killed 1, survived 0, not covered 1, timeout 0); sites left out: 0'
full 1 '--fail-on-survived' FLIXW_PROJECT_ROOT="$work/uncovered" FLIXW_COMPILER_JAR="$flix_jar"
contains out 'not covered src/A.flix:3:'
fi
printf 'test-plugin: %s passed, 0 failed\n' "$checks"
