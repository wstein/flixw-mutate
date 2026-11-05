"""Draws docs/design/figures/ratings.svg from docs/design/data/ratings.csv.

Usage: python3 plot-ratings.py <ratings.csv> <ratings.svg>
Output is deterministic (fixed hash salt, no date metadata), so CI can detect a stale figure.
"""
import csv
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ACCENT = "#2F6FDB"
MUTED = "#B8BCC4"
INK = "#1F2328"
QUIET = "#5F6670"
GRID = "#E5E7EB"

# Label placement per option id: (dx, dy, horizontal alignment), in points.
# Kept here rather than in the CSV because it is layout, not data.
OFFSETS = {
    4: (0, 10, "center"),
    5: (8, -4, "left"),
    6: (-9, -4, "right"),
    7: (8, 4, "left"),
    8: (8, -12, "left"),
}


def main(src: str, out: str) -> None:
    with open(src, newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))

    plt.rcParams.update({
        "svg.hashsalt": "flixw-mutate",
        "font.family": "DejaVu Sans",
        "font.size": 10,
    })
    fig, ax = plt.subplots(figsize=(8.0, 4.8), dpi=100)

    # Grey first, then the recommended options on top.
    ordered = sorted(rows, key=lambda r: r["recommended"] == "true")
    for r in ordered:
        rec = r["recommended"] == "true"
        x, y = int(r["effort"]), int(r["value"])
        ax.scatter(x, y, s=70, color=ACCENT if rec else MUTED, zorder=3,
                   edgecolors="white", linewidths=1)
        dx, dy, ha = OFFSETS.get(int(r["id"]), (8, -4, "left"))
        ax.annotate(f'{r["id"]} {r["name"]} · {r["overall"]}/10', (x, y),
                    xytext=(dx, dy), textcoords="offset points", ha=ha,
                    fontsize=9.5, color=INK if rec else QUIET,
                    fontweight="bold" if rec else "normal")

    recommended = sum(r["recommended"] == "true" for r in rows)
    fig.suptitle("Datalog mutation at the solver and mutant schemata: most value for the effort",
                 x=0.02, ha="left", fontsize=11.5, color=INK, fontweight="bold")
    ax.set_title(f"{len(rows)} options scored 1–10 on value and effort; "
                 f"the {recommended} in blue form the recommended build path",
                 loc="left", fontsize=9.5, color=QUIET)

    ax.set_xlim(0, 10)
    ax.set_ylim(2, 10)
    ax.set_xlabel("Effort (1 = least, 10 = most)", color=QUIET)
    ax.set_ylabel("Value (10 = most)", color=QUIET)
    ax.grid(True, color=GRID, linewidth=0.8, zorder=0)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(MUTED)
    ax.tick_params(colors=QUIET)

    fig.tight_layout(rect=(0, 0, 1, 0.97))
    fig.savefig(out, format="svg", metadata={"Date": None})
    print(f"rendered {out}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])
