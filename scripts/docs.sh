#!/bin/sh
# Regenerates the design document's figures and renders its PDF.
#
#   sh scripts/docs.sh          figures + PDF (docs/design/_build/DESIGN.pdf)
#   sh scripts/docs.sh figures  figures only
#
# Needs: node + npm, python3 with matplotlib, rsvg-convert (librsvg),
# and quarto (bundles Typst) for the PDF.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
design="$root/docs/design"
figures="$design/figures"

(cd "$design/tools" && npm ci --no-audit --no-fund --silent)
node "$design/tools/render-d2.mjs" "$design/diagrams" "$figures"

# D2 embeds its fonts as CSS, which Typst's SVG renderer ignores. Converting the text to
# outlines makes GitHub and the PDF show exactly the same picture. A "# print-width: N"
# line in the .d2 file sets the figure's natural width in px (Typst: 1px = 0.75pt).
for src in "$design"/diagrams/*.d2; do
  name=$(basename "$src" .d2)
  width=$(sed -n 's/^# print-width: *\([0-9][0-9]*\).*/\1/p' "$src")
  tmp="$figures/$name.outlined.svg"
  if [ -n "$width" ]; then
    rsvg-convert -f svg -a -w "$width" "$figures/$name.svg" -o "$tmp"
  else
    rsvg-convert -f svg "$figures/$name.svg" -o "$tmp"
  fi
  mv "$tmp" "$figures/$name.svg"
done

python3 "$design/tools/plot-ratings.py" "$design/data/ratings.csv" "$figures/ratings.svg"

if [ "${1:-}" = "figures" ]; then
  exit 0
fi

(cd "$design" && quarto render --to typst)
echo "wrote $design/_build/DESIGN.pdf"
