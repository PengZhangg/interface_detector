"""Summarise ReferenceAnalyzer output (one references.json per project).

Usage: python reference_analysis.py <dir-of-references-json> <out-dir>

Each <project>.json in the input directory is the output of
interfacedetector.ReferenceAnalyzer for that project. Writes a stacked bar
chart and a markdown table that break each project's zero-implementation
interfaces down by what (if anything) still keeps them alive.
"""
import json
import sys
from pathlib import Path

import common  # noqa: F401  (configures matplotlib)
import matplotlib.pyplot as plt
import numpy as np

# ReferenceAnalyzer categories, from "clearly in use" to "clearly dead"
GROUPS = {
    "Implemented indirectly": ["implementedViaSubInterface", "anonymousOrUnresolvedImpl",
                               "implementedByCodegen"],
    "Referenced by live code": ["referenced", "referencedLikelyLambda", "namespaceOnly"],
    "Dead chain": ["onlyInUncalledMethods", "onlyExtendedByUnusedInterfaces"],
    "Unreferenced": ["onlyImportOrJavadoc", "unreferenced"],
}
# greyscale + hatching so charts print in black and white
SEGMENT_STYLES = [
    {"color": "white", "hatch": "///"},
    {"color": "#d9d9d9", "hatch": "..."},
    {"color": "#a6a6a6", "hatch": "xx"},
    {"color": "#595959", "hatch": ""},
]

# what callers pass where a zero-impl interface is a parameter type
PARAM_GROUPS = {
    "Implemented indirectly": ["explained"],
    "Inline implementation": ["receivesInlineImplementation"],
    "Value obtained elsewhere": ["valueFromElsewhere", "frameworkInjected"],
    "Never supplied": ["neverCalled", "onlyNullPassed"],
}
EXPLAINED = {"implementedViaSubInterface", "implementedByCodegen"}


def param_bucket(entry: dict) -> str | None:
    if not entry.get("parameterVerdict"):
        return None
    return "explained" if entry["category"] in EXPLAINED else entry["parameterVerdict"]


def load(in_dir: Path) -> list[dict]:
    projects = []
    for path in sorted(in_dir.glob("*.json")):
        with open(path) as f:
            data = json.load(f)
        data["_project"] = path.stem
        projects.append(data)
    return projects


def group_counts(entries: list[dict]) -> list[int]:
    return [sum(1 for e in entries if e["category"] in cats) for cats in GROUPS.values()]


def save_chart(projects: list[dict], out: Path, handwritten_only: bool, filename: str) -> None:
    rows = []
    for p in projects:
        entries = [e for e in p["interfaces"] if not (handwritten_only and e["generated"])]
        if entries:
            rows.append((p["_project"], group_counts(entries), len(entries)))
    draw_stacked(rows, list(GROUPS), "Share of zero-implementation interfaces (%)", out, filename)


def param_counts(entries: list[dict]) -> list[int]:
    buckets = [param_bucket(e) for e in entries]
    return [sum(1 for b in buckets if b in keys) for keys in PARAM_GROUPS.values()]


def save_param_chart(projects: list[dict], out: Path) -> None:
    rows = []
    for p in projects:
        entries = [e for e in p["interfaces"] if not e["generated"] and param_bucket(e)]
        if entries:
            rows.append((p["_project"], param_counts(entries), len(entries)))
    draw_stacked(rows, list(PARAM_GROUPS),
                 "Share of zero-impl interfaces used as a parameter type (%)", out, "zero_impl_parameters.png")


def draw_stacked(rows: list[tuple], labels: list[str], xlabel: str, out: Path, filename: str) -> None:
    rows.sort(key=lambda r: r[2])

    names = [f"{r[0]} (n={r[2]})" for r in rows]
    shares = np.array([[c / r[2] * 100 for c in r[1]] for r in rows])

    fig, ax = plt.subplots(figsize=(9, 0.62 * len(rows) + 1.6))
    y = np.arange(len(rows))
    bar_height = 0.5
    plt.rcParams["hatch.linewidth"] = 0.6
    label_box = {"boxstyle": "square,pad=0.15", "facecolor": "white", "edgecolor": "none"}

    left = np.zeros(len(rows))
    for i, (label, style) in enumerate(zip(labels, SEGMENT_STYLES)):
        ax.barh(y, shares[:, i], left=left, color=style["color"], hatch=style["hatch"],
                edgecolor="black", linewidth=0.8, height=bar_height, label=label)
        left += shares[:, i]

    # wide segments are labelled inside; narrow ones above the bar with a leader line
    min_gap = 4.5  # minimum distance between narrow labels, in % units
    for yi in range(len(rows)):
        start = 0.0
        narrow = []  # (segment centre, text)
        for s in shares[yi]:
            if s > 0:
                text = f"{s:.0f}%" if s >= 1 else f"{s:.1f}%"
                centre = start + s / 2
                if s >= 6:
                    ax.text(centre, yi, text, ha="center", va="center", fontsize=7, bbox=label_box)
                else:
                    narrow.append((centre, text))
            start += s

        # spread narrow labels apart, keeping them inside the plot
        positions = [c for c, _ in narrow]
        for k in range(len(positions) - 2, -1, -1):
            positions[k] = min(positions[k], positions[k + 1] - min_gap)
        for k in range(1, len(positions)):
            positions[k] = max(positions[k], positions[k - 1] + min_gap)

        edge = yi + bar_height / 2
        for (centre, text), x in zip(narrow, positions):
            ax.annotate(text, xy=(centre, edge), xytext=(x, edge + 0.22),
                        ha="center", va="bottom", fontsize=6.5,
                        arrowprops={"arrowstyle": "-", "linewidth": 0.6, "color": "black",
                                    "shrinkA": 0, "shrinkB": 0},
                        annotation_clip=False)

    ax.set_yticks(y)
    ax.set_yticklabels(names, fontsize=8)
    ax.set_xlim(0, 100)
    ax.set_xlabel(xlabel)
    ax.legend(ncol=2 if max(map(len, labels)) > 25 else 4, loc="lower center",
              bbox_to_anchor=(0.5, 1.0), frameon=False, fontsize=8)
    fig.tight_layout()
    fig.savefig(out / filename, dpi=200)
    plt.close(fig)
    print(f"  Saved {filename}")


def save_table(projects: list[dict], out: Path) -> None:
    header = ["Project", "Zero-impl", "Generated"] + list(GROUPS) + ["Unreferenced (hand-written)"]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    totals = np.zeros(len(header) - 1, dtype=int)
    for p in sorted(projects, key=lambda p: -p["zeroImplInterfaces"]):
        entries = p["interfaces"]
        generated = sum(e["generated"] for e in entries)
        counts = group_counts(entries)
        dead_hand = sum(1 for e in entries if not e["generated"]
                        and e["category"] in GROUPS["Unreferenced"])
        row = [len(entries), generated, *counts, dead_hand]
        totals += row
        lines.append("| " + " | ".join([p["_project"], *map(str, row)]) + " |")
    lines.append("| **Total** | " + " | ".join(f"**{t}**" for t in totals) + " |")
    (out / "reference_summary.md").write_text("\n".join(lines) + "\n")
    print("  Saved reference_summary.md")


def save_param_table(projects: list[dict], out: Path) -> None:
    header = ["Project", "Used as parameter"] + list(PARAM_GROUPS)
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    totals = np.zeros(len(header) - 1, dtype=int)
    for p in projects:
        entries = [e for e in p["interfaces"] if not e["generated"] and param_bucket(e)]
        row = [len(entries), *param_counts(entries)]
        totals += row
        lines.append("| " + " | ".join([p["_project"], *map(str, row)]) + " |")
    lines.append("| **Total** | " + " | ".join(f"**{t}**" for t in totals) + " |")
    (out / "parameter_summary.md").write_text("\n".join(lines) + "\n")
    print("  Saved parameter_summary.md")


def main() -> None:
    in_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    projects = load(in_dir)
    save_chart(projects, out_dir, False, "zero_impl_references.png")
    save_chart(projects, out_dir, True, "zero_impl_references_handwritten.png")
    save_table(projects, out_dir)
    save_param_chart(projects, out_dir)
    save_param_table(projects, out_dir)


if __name__ == "__main__":
    main()
