# flixw-mutate

Mutation testing for [Flix](https://flix.dev) projects, as a [flixw](https://github.com/wstein/flixw) plugin (`./flixw mutate`). Status: first version of the engine for functional code (design option 4, mutant schemata); Datalog mutation (option 6) is not built. Start with the [design document](docs/design/DESIGN.md). A typeset PDF is built by CI (`docs` workflow, `design-pdf` artifact) or locally with `sh scripts/docs.sh`.

The rest of this README is the plugin template it was created from.

## flixw Java plugin template

A small starting point with no third-party dependency for a third-party
[flixw](https://github.com/wstein/flixw) plugin. It produces one executable Java 21 JAR
and consumes flixw ABI version 1.

## Build

The plugin embeds the SDK module `kit` from the exact source revision in
`sdk-source.properties` (`repository`, full `revision`, `module=kit`). Clone that
repository, check out the `revision`, and point `FLIXW_SDK_SOURCE` at the checkout root:

```sh
export FLIXW_SDK_SOURCE=/path/to/flixw-plugin-sdk   # clean checkout at the pinned revision
sh scripts/package.sh 0.1.0
```

`scripts/check-sdk-source.sh` runs first and fails on a wrong HEAD, a missing path, tracked,
untracked or ignored changes under `kit/src` and `kit/resources`, or consumed bytes that differ
from the pinned Git blobs. There is no download or sibling-checkout fallback; CI checks out the
same revision. Only `kit/src` and `kit/resources` enter `dist/plugin.jar`, with
`META-INF/LICENSE-flixw-plugin-sdk`; no SDK JAR is needed at run time. The pin names a commit
because the SDK has no release asset yet. Replacing it with a versioned release asset and its
SHA-256 is a separate step.

This writes `dist/plugin.jar` and `dist/SHA256SUMS`; the same source, pin, version and JDK give the
same JAR (the manifest records the JDK as `Created-By`). `sh scripts/test.sh` runs the source checks (workflow pins, pin guard, packaged contents,
plugin contract, paths with spaces, reproducibility) in a temporary directory, not in `dist/`.
`FLIXW_MUTATE_DIST` overrides the output directory. Publish both files as immutable release
assets, then install the JAR explicitly from a tagged release:

```sh
./flixw plugin install acme-example 0.1.0 \
  https://github.com/acme/acme-flixw-plugin/releases/download/v0.1.0/plugin.jar \
  --sha256 "$(awk '/plugin.jar$/ { print $1 }' dist/SHA256SUMS)"
```

The install records the exact build in the project's `.flixw/lock.toml` when one exists.
That record chooses an already-installed artifact; it never downloads a plugin by itself.

## Shell and report header

`PluginMain` uses the kit for the flixw context (`Context`), the shared exit codes
(`ExitCodes`: 0 success, 1 policy finding, 2 shell or context error) and `Usage` errors. A run
prints a header with the three version levels:

```text
flixw-mutate plugin=<plugin version> sdk=<pinned SDK commit> flix=<FLIXW_COMPILER_VERSION>
```

`plugin` is the JAR's `Implementation-Version`, `sdk` the revision of `sdk-source.properties`
embedded in the JAR, `flix` the flixw ABI variable. Without a lock the compiler variables are
absent: exit 2. Unknown arguments are a shell error (exit 2).
The kit's cache (`KeyedStore`) is embedded but not used yet.

## Mutation run (option 4)

```sh
./flixw mutate [--timeout-ms <ms>] [--no-selection] [--fail-on-survived]
```

The project's `src/**/*.flix` files are mutated, its `test/**/*.flix` files and the `@Test` functions in all
files are the tests. Sites are picked from the typed AST: comparisons (`<`, `<=`, `>`, `>=`, `==`, `!=`),
numeric `+ - * /`, `and`, `or` and `not`. Each site becomes a call into a generated module `FlixwMutant`
(a switch and coverage marker, the Java class `dev.flixw.mutate.Marker` from a small jar handed to the compiler,
called through `unsafe IO`); the operands are not duplicated and the right operand of `and`/`or` stays lazy.
One worker JVM (the project's Java, with the pinned compiler) compiles and loads the rewritten program once.
A baseline run with every switch off records which test evaluates which site. A mutant is a switch number and a
rerun of the tests that evaluate its site, which stops at the first failing test; a mutant that no test
evaluates is `not covered` (survived without a run). `--no-selection` runs every test for every mutant, for
comparison. The report lists every mutant as `killed`, `survived`, `not covered` or `timeout`, the tests per
mutant (mean, median, maximum) and the test executions against those of a full run (computed from the position
of the killing test; for a timeout the executions are not measured and counted as all selected tests, an upper bound), and the score (killed or timed out, of all mutants). A mutant that exceeds the timeout (default: ten times the
baseline run, at least 2000 ms) ends the worker; the next one loads the program again.

Exit codes: 0 report written, 1 survivors with `--fail-on-survived`, 2 the project does not compile, its tests
fail without a mutation, it has no tests, or a worker failed.

Limits of the run:
- A test counts as failed when it throws. `flix test` (0.79.0) also counts a test with output on stderr as failed;
  the worker does not: a mutant that only makes a test write to stderr survives.
- There is no `invalid` outcome. If the rewritten program does not compile (for example a project module
  named `FlixwMutant`), the run stops with exit 2 and the compiler message.
- The work directory (`flixw-mutate*` in the temporary directory) is removed on exit and on SIGTERM
  (shutdown hook); after SIGKILL it stays. The worker JVM ends when the engine's stdin pipe closes.
- The selection assumes that a test which does not evaluate a mutant's site behaves as in the baseline: tests
  with threads or state shared between tests are not covered by the checks, and the optimiser may merge or move
  calls of the pure-typed switch (the tests compare selected and full runs on two fixtures, no proof). The
  Datalog route (option 6) has no marker yet.
- Options given twice (`--timeout-ms`, `--no-selection`, `--fail-on-survived`) are an error; `--fail-on-survived`
  counts `not covered` mutants as survivors.

Not built yet: `Some`/`None`, `Ok`/`Err`, swapped match bodies, removed `do Op`, swapped operands, lattice
operators, Datalog (option 6), PIT-format `mutations.xml`, equivalent-mutant filtering, dependencies from
`flix.toml` packages. Tested on Flix 0.79.0 only; the compiler is reached by reflection in `FlixApi`.

## ABI and safety

Plugins receive their command-line arguments normally and run with the selected project
JVM. Read common context from `FLIXW_*` environment variables; `FLIXW_CONTEXT` names a
fresh UTF-8 JSON file for structured data. Check `FLIXW_ABI_VERSION`, use
`FLIXW_PROJECT_ROOT` for project-relative work, and ignore unknown JSON fields because the
ABI is additive.

The compiler fields are absent when the project has no lock. Do not assume a compiler JAR
is available unless the plugin actually needs one.

A SHA-256 proves that an installed artifact has the expected bytes. It does not make the
plugin safe: plugins are unsandboxed code running as their caller. Publish immutable tag
URLs, ask users to supply `--sha256`, and do not place secrets or whole environment dumps
in output.

See flixw's [plugin contract](https://github.com/wstein/flixw/blob/main/docs/CONTRACT.md#flixw-plugin)
for the complete ABI.

## License

Apache-2.0, see `LICENSE`.
