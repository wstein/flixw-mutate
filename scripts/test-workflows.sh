#!/bin/sh
# Every checkout step is pinned to a full commit SHA and drops the token after checkout.
#
# This is a line-based check, not a YAML parser. It understands one subset: a checkout step is
#     - uses: actions/checkout@<40 hex>        (optionally quoted, "- uses:" first in the step)
#       with:                                  (once, two spaces under "uses")
#         persist-credentials: false           (once, two more spaces, plain scalar)
# plus other plain "key: value" lines at those two indents. Everything else in a step that
# mentions actions/checkout is an error, not a pass: a space before ":", quoted keys, tabs,
# duplicate keys, nested maps, lists, flow maps, anchors and aliases, block scalars, other
# indents, other case, tags and short SHAs. Fixtures below run each form against the check.
# GitHub's own handling of duplicate keys is UNKNOWN here; the check refuses them regardless.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/mutate workflows.XXXXXX")
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# check_dir <dir>: prints the number of checkout steps; messages go to stderr; fails on a violation.
check_dir() {
  found=0
  for file in "$1"/*.yml "$1"/*.yaml; do
    [ -f "$file" ] || continue
    count=$(awk -v name="${file##*/}" '
      function fail(msg) { printf "test-workflows: %s:%d %s\n", name, NR, msg > "/dev/stderr"; bad = 1 }
      function finish() {
        if (open && persist != 1) { printf "test-workflows: %s:%d checkout needs exactly one persist-credentials: false under with:\n", name, line > "/dev/stderr"; bad = 1 }
        open = 0; with = 0; persist = 0
        delete step_keys; delete with_keys
      }
      function plain(v) { return v != "" && v !~ /^[{\[&*|>!%@`]/ }
      { raw = $0; text = $0; sub(/[ \t]#.*$/, "", text); sub(/^[ \t]*#.*$/, "", text) }
      open && raw ~ /\t/ { fail("tab in a checkout step"); next }
      text ~ /^[ \t]*$/ { next }
      { indent = match(text, /[^ ]/) - 1 }
      open && indent <= step_indent { finish() }
      open {
        if (text ~ /^ *- /) { fail("list inside a checkout step"); next }
        if (text !~ /^ *[A-Za-z0-9_-]+:( +[^ ].*)?$/) { fail("unsupported line in a checkout step"); next }
        key = text; sub(/^ */, "", key); sub(/:.*$/, "", key)
        value = text; sub(/^ *[A-Za-z0-9_-]+: */, "", value); sub(/ +$/, "", value)
        if (indent == step_indent + 2) {
          if (key in step_keys) { fail("duplicate key " key " in a checkout step"); next }
          step_keys[key] = 1
          with = (key == "with")
          if (with) { if (value != "") fail("with: must open a block"); next }
          if (!plain(value)) fail("unsupported value for " key)
        } else if (with && indent == step_indent + 4) {
          if (key in with_keys) { fail("duplicate key " key " under with:"); next }
          with_keys[key] = 1
          if (!plain(value)) { fail("unsupported value for " key); next }
          if (key == "persist-credentials") {
            if (value ~ /^["'\'']?false["'\'']?$/) persist++; else fail("persist-credentials is not false")
          }
        } else fail("unsupported nesting in a checkout step")
        next
      }
      tolower(text) ~ /actions\/checkout/ {
        if (text !~ /^ *- uses: ["'\'']?actions\/checkout@[0-9a-f]{40}["'\'']?$/) { fail("unsupported or unpinned checkout reference"); n++; next }
        open = 1; persist = 0; with = 0; line = NR; n++; step_indent = indent
      }
      END { finish(); print n + 0; exit bad }
    ' "$file") || return 1
    found=$((found + count))
  done
  echo "$found"
}
sha=3d3c42e5aac5ba805825da76410c181273ba90b1
tab=$(printf '\t')
head_='jobs:
  a:
    steps:
'
checks=0
count=$(check_dir "$root/.github/workflows") || exit 1
[ "$count" -gt 0 ] || { echo 'test-workflows: no checkout steps found' >&2; exit 1; }
checks=$((checks + count))
# fixture <name> <yes|no> <step lines> [file name]: "yes" must pass, "no" must fail.
fixture() {
  mkdir -p "$work/$1"
  printf '%s%s\n' "$head_" "$3" > "$work/$1/${4:-w.yml}"
  if out=$(check_dir "$work/$1" 2>/dev/null); then ok=yes; else ok=no; fi
  [ "$ok" = "$2" ] || { echo "test-workflows: fixture $1: accepted=$ok, expected $2 ($out)" >&2; exit 1; }
  checks=$((checks + 1))
}
# fixture_says <name> <step lines> <message>: the check must fail and say <message>. Rules that another
# rule also catches (a missing persist-credentials, say) only show up through their own message.
fixture_says() {
  mkdir -p "$work/$1"
  printf '%s%s\n' "$head_" "$2" > "$work/$1/w.yml"
  if out=$(check_dir "$work/$1" 2>&1); then
    echo "test-workflows: fixture $1: accepted, expected the message: $3" >&2; exit 1
  fi
  case $out in *"$3"*) ;; *) echo "test-workflows: fixture $1: expected the message \"$3\", got: $out" >&2; exit 1 ;; esac
  checks=$((checks + 1))
}
u="      - uses: actions/checkout@$sha"
w='        with:'
p='          persist-credentials: false'
fixture good yes "$u # v7
$w
$p"
fixture good-quoted yes "      - uses: \"actions/checkout@$sha\"
$w
$p
          ref: main"
fixture yaml-extension no "      - uses: actions/checkout@v4
$w
$p" w.yaml
fixture tag no "      - uses: actions/checkout@v4
$w
$p"
fixture short-sha no "      - uses: actions/checkout@3d3c42e
$w
$p"
fixture uppercase no "      - uses: Actions/checkout@$sha
$w
$p"
fixture no-with no "$u"
fixture comment-only no "$u
$w
          # persist-credentials: false"
fixture true no "$u
$w
          persist-credentials: true # false"
fixture quoted-key no "      - \"uses\": actions/checkout@$sha
$w
$p"
fixture block-scalar no "      - uses: >-
          actions/checkout@$sha
$w
$p"
fixture uses-without-value no "      - uses:
          actions/checkout@$sha
$w
$p"
fixture space-before-colon no "      - uses : actions/checkout@$sha
$w
$p"
fixture with-space-before-colon no "$u
        with :
$p"
fixture persist-space-before-colon no "$u
$w
          persist-credentials : false"
fixture persist-under-env no "$u
        env:
$p"
fixture persist-after-with no "$u
$w
          repository: x
        env:
$p"
fixture false-then-true no "$u
$w
$p
          persist-credentials: true"
fixture true-then-false no "$u
$w
          persist-credentials: true
$p"
fixture duplicate-with no "$u
$w
$p
$w"
fixture nested-map no "$u
$w
          persist-credentials:
            value: false"
fixture quoted-nested-key no "$u
$w
          \"persist-credentials\": false"
fixture list-under-with no "$u
$w
$p
          - item"
fixture flow-map no "$u
        with: {persist-credentials: false}"
fixture anchor no "$u
$w
          persist-credentials: &p false"
fixture alias no "$u
        with: *w"
fixture tab-indent no "$u
$w
${tab}${tab}persist-credentials: false"
fixture other-indent no "$u
$w
         persist-credentials: false"
fixture step-level-map no "$u
        env:
          X: 1
$w
$p"
fixture second-checkout-unpinned no "$u
$w
$p
      - uses: actions/checkout@v4
$w
$p"
fixture with-prefixed-key no "$u
        without:
$p"
fixture_says tab-in-comment "$u
$w
$p$tab# c" "tab in a checkout step"
fixture_says line-without-colon "$u
$w
$p
          no colon here" "unsupported line in a checkout step"
fixture_says with-scalar "$u
        with: x
$p" "with: must open a block"
fixture_says step-level-anchor "$u
        name: &x y
$w
$p" "unsupported value for name"
fixture_says with-level-anchor "$u
$w
$p
          ref: &x main" "unsupported value for ref"
fixture_says persist-true-alone "$u
$w
          persist-credentials: true" "persist-credentials is not false"
fixture_says nesting-three-spaces "$u
$w
$p
         ref: main" "unsupported nesting in a checkout step"
printf 'test-workflows: %s passed, 0 failed\n' "$checks"
