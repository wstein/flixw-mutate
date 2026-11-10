#!/bin/sh
# Fetch Flix example and test sources at the pinned revision of flix/flix. Nothing is copied into this
# repository (Apache-2.0 sources of another project); the files go to <target-dir> and each one is
# checked against the sha256 in common/flix-material.sha256.
#
# Usage: sh spikes/common/fetch-flix-material.sh <target-dir>
# Pin: tag v0.79.0 = commit 36d923ca325dc466b28c63419667e6026a80f920 (flix/flix).
set -eu
umask 022
rev=36d923ca325dc466b28c63419667e6026a80f920
here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)
[ "$#" -eq 1 ] || { echo "usage: fetch-flix-material.sh <target-dir>" >&2; exit 2; }
dir=$1
if command -v sha256sum >/dev/null 2>&1; then sum="sha256sum"; else sum="shasum -a 256"; fi
mkdir -p "$dir"
while read -r hash path; do
  case $hash in ''|'#'*) continue;; esac
  out=$dir/$(basename "$path")
  curl -fsSL --retry 3 --connect-timeout 30 --max-time 120 -o "$out.part" \
    "https://raw.githubusercontent.com/flix/flix/$rev/$path" || { rm -f "$out.part"; echo "fetch failed: $path" >&2; exit 1; }
  got=$($sum "$out.part" | cut -d' ' -f1)
  [ "$got" = "$hash" ] || { rm -f "$out.part"; echo "sha256 mismatch: $path (expected $hash, got $got)" >&2; exit 1; }
  mv "$out.part" "$out"
done < "$here/flix-material.sha256"
echo "$dir"
