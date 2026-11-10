#!/bin/sh
# Verify the exact local source dependency before compiling any plugin or SDK source.
set -eu
# A local replacement ref must not substitute different bytes for the pinned object.
export GIT_NO_REPLACE_OBJECTS=1
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
pin=${1:-$root/sdk-source.properties}
fail() { printf 'sdk-source: %s\n' "$*" >&2; exit 2; }
[ -f "$pin" ] || fail "missing pin file: $pin"
property() {
  awk -v key="$1" '
    { sub(/\r$/, "") }
    index($0, key "=") == 1 { value = substr($0, length(key) + 2); count++ }
    END { if (count == 1) print value; else exit 1 }
  ' "$pin" || fail "expected one $1 property"
}
repository=$(property repository)
revision=$(property revision)
module=$(property module)
[ "$repository" = 'https://github.com/wstein/flixw-plugin-sdk' ] || fail 'unsupported repository in pin'
[ "$module" = kit ] || fail 'unsupported module in pin (expected kit)'
[ "${#revision}" -eq 40 ] || fail 'revision must be a full 40-character lowercase commit SHA'
case $revision in *[!0-9a-f]*) fail 'revision must be a full 40-character lowercase commit SHA' ;; esac
[ -n "${FLIXW_SDK_SOURCE:-}" ] || fail 'set FLIXW_SDK_SOURCE to the pinned SDK checkout'
[ -d "$FLIXW_SDK_SOURCE" ] || fail "FLIXW_SDK_SOURCE is not a directory: $FLIXW_SDK_SOURCE"
source=$(CDPATH= cd -- "$FLIXW_SDK_SOURCE" && pwd -P)
top=$(git -C "$source" rev-parse --show-toplevel 2>/dev/null) || fail 'FLIXW_SDK_SOURCE is not a Git checkout'
[ "$top" = "$source" ] || fail 'FLIXW_SDK_SOURCE must name the checkout root'
head=$(git -C "$source" rev-parse HEAD) || fail 'cannot read SDK HEAD'
[ "$head" = "$revision" ] || fail "SDK HEAD is $head, expected $revision"
status=$(git -C "$source" status --porcelain --untracked-files=all) || fail 'cannot inspect SDK source changes'
[ -z "$status" ] || fail 'SDK sources have changed (tracked or untracked files); use a clean pinned checkout'
ignored=$(git -C "$source" ls-files --others --ignored --exclude-standard -- kit/src kit/resources) || fail 'cannot inspect ignored SDK inputs'
[ -z "$ignored" ] || fail 'SDK inputs contain ignored files under kit/src or kit/resources'
# Status honors assume-unchanged and skip-worktree. Compare consumed bytes to HEAD as well.
tracked=$(mktemp)
trap 'rm -f "$tracked"' EXIT INT TERM
git -C "$source" ls-tree -r HEAD -- kit/src kit/resources > "$tracked" || fail 'cannot read pinned SDK tree'
while IFS="$(printf '\t')" read -r entry path; do
  expected=${entry##* }
  case $entry in
    '100644 blob '*|'100755 blob '*) ;;
    *) fail 'SDK inputs must be tracked regular files' ;;
  esac
  case $path in
    \"*) fail 'SDK input path requires unsupported Git quoting' ;;
  esac
  [ ! -L "$source/$path" ] || fail "SDK input changed into a symbolic link: $path"
  actual=$(git -C "$source" hash-object --no-filters -- "$path") || fail "SDK input changed: $path"
  [ "$actual" = "$expected" ] || fail "SDK input bytes have changed: $path"
done < "$tracked"
[ -d "$source/kit/src" ] || fail 'SDK kit/src is missing'
[ -d "$source/kit/resources" ] || fail 'SDK kit/resources is missing'
printf '%s\n' "$source"
