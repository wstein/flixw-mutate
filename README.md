# flixw Java plugin template

A small, dependency-free starting point for a third-party
[flixw](https://github.com/wstein/flixw) plugin. It produces one executable Java 21 JAR
and consumes flixw ABI version 1.

## Build

```sh
sh scripts/package.sh 0.1.0
```

This writes `dist/plugin.jar` and `dist/SHA256SUMS`. Publish both as immutable release
assets, then install the JAR explicitly from a tagged release:

```sh
./flixw plugin install acme-example 0.1.0 \
  https://github.com/acme/acme-flixw-plugin/releases/download/v0.1.0/plugin.jar \
  --sha256 "$(awk '/plugin.jar$/ { print $1 }' dist/SHA256SUMS)"
```

The install records the exact build in the project's `.flixw/lock.toml` when one exists.
That record chooses an already-installed artifact; it never downloads a plugin by itself.

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
