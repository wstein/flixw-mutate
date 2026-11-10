#!/bin/sh
# Inspect the single plugin artifact: kit classes, the SDK license and the pin must be embedded.
set -eu
plugin=${1:?usage: sh scripts/check-sdk-package.sh plugin.jar}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
checks=0
for class in Context Usage Launcher InspectionLoader AtomicOutput KeyedStore ProvenanceData ExitCodes; do
  unzip -Z1 "$plugin" | grep -qx "dev/flixw/sdk/kit/$class.class" || {
    echo "sdk-package: missing embedded kit class $class" >&2; exit 1;
  }
  checks=$((checks + 1))
done
unzip -p "$plugin" META-INF/LICENSE-flixw-plugin-sdk | grep -q 'Apache License' || {
  echo 'sdk-package: missing SDK license' >&2; exit 1;
}
checks=$((checks + 1))
# The header reads the pin from the artifact, so it must be the pin of the source tree.
unzip -p "$plugin" dev/flixw/plugin/sdk-source.properties | cmp -s - "$root/sdk-source.properties" || {
  echo 'sdk-package: embedded pin differs from sdk-source.properties' >&2; exit 1;
}
checks=$((checks + 1))
printf 'sdk-package: %s passed, 0 failed\n' "$checks"
