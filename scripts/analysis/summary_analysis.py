import json
import os
import sys
from pathlib import Path

os.environ.setdefault(
    "MPLCONFIGDIR", str(Path(__file__).resolve().parent / ".matplotlib-cache")
)

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

COLORS = {
    "public": "#a3be8c",
    "package_private": "#d08770",
    "concrete": "#ebcb8b",
    "abstract": "#81a1c1",
    "scatter": "#5e81ac",
    "zero_impl": "#bf616a",
    "single_impl": "#5e81ac",
}


def load_projects(output_dir: Path) -> list[dict]:
    projects = []
    for folder in sorted(output_dir.iterdir()):
        json_path = folder / "output.json"
        if folder.is_dir() and json_path.exists():
            with open(json_path) as f:
                data = json.load(f)
            data["_project"] = folder.name.removesuffix("Output")
            projects.append(data)
    return projects


def grouped_bar_with_pct(ax, names, left_vals, right_vals, left_label, right_label,
                          left_color, right_color, ylabel, title):
    left_arr = np.array(left_vals, dtype=float)
    right_arr = np.array(right_vals, dtype=float)
    totals = left_arr + right_arr
    left_pct = np.where(totals > 0, left_arr / totals * 100, 0)
    right_pct = np.where(totals > 0, right_arr / totals * 100, 0)

    x = np.arange(len(names))
    width = 0.35
    bars_l = ax.bar(x - width / 2, left_vals, width, label=left_label, color=left_color)
    bars_r = ax.bar(x + width / 2, right_vals, width, label=right_label, color=right_color)

    y_nudge = ax.get_ylim()[1] * 0.005
    for bar, pct in zip(bars_l, left_pct):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + y_nudge,
                f"{pct:.0f}%", ha="center", va="bottom", fontsize=6.5, color="#555")
    for bar, pct in zip(bars_r, right_pct):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + y_nudge,
                f"{pct:.0f}%", ha="center", va="bottom", fontsize=6.5, color="#555")

    ax.set_title(title)
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=45, ha="right", fontsize=8)
    ax.legend()


def save_visibility_ratio(projects: list[dict], out: Path) -> None:
    names, pub_counts, pkg_counts = [], [], []
    for p in projects:
        names.append(p["_project"])
        pub_counts.append(sum(1 for i in p["interfaces"] if i["visibility"] == "public"))
        pkg_counts.append(sum(1 for i in p["interfaces"] if i["visibility"] == "package-private"))

    fig, ax = plt.subplots(figsize=(14, 5))
    grouped_bar_with_pct(ax, names, pub_counts, pkg_counts,
                         "Public", "Package-private",
                         COLORS["public"], COLORS["package_private"],
                         "Interface count", "Public vs Package-Private Interfaces per Project")
    fig.tight_layout()
    fig.savefig(out / "visibility_ratio.png", dpi=160)
    plt.close(fig)
    print(f"  Saved visibility_ratio.png")


def save_impl_count_distribution(projects: list[dict], out: Path) -> None:
    counts = [
        len(i["concreteImplementations"]) + len(i["abstractImplementations"])
        for p in projects
        for i in p["interfaces"]
    ]

    cap = min(max(counts, default=10), 30)
    filtered = [c for c in counts if c <= cap]
    excluded = len(counts) - len(filtered)

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.hist(filtered, bins=[i - 0.5 for i in range(0, cap + 2)],
            color=COLORS["concrete"], edgecolor="white", linewidth=0.5)
    ax.set_xlim(-0.5, cap + 0.5)
    ax.set_title("Implementation Count Distribution (all interfaces, all projects)")
    xlabel = "Number of implementations per interface"
    if excluded:
        xlabel += f"  ({excluded} interface(s) with >{cap} implementations not shown)"
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Number of interfaces")
    ax.set_xticks(range(0, cap + 1))
    fig.tight_layout()
    fig.savefig(out / "impl_count_distribution.png", dpi=160)
    plt.close(fig)
    print(f"  Saved impl_count_distribution.png")


def save_abstract_vs_concrete(projects: list[dict], out: Path) -> None:
    names, concrete_totals, abstract_totals = [], [], []
    for p in projects:
        names.append(p["_project"])
        concrete_totals.append(sum(len(i["concreteImplementations"]) for i in p["interfaces"]))
        abstract_totals.append(sum(len(i["abstractImplementations"]) for i in p["interfaces"]))

    fig, ax = plt.subplots(figsize=(14, 5))
    grouped_bar_with_pct(ax, names, concrete_totals, abstract_totals,
                         "Concrete", "Abstract",
                         COLORS["concrete"], COLORS["abstract"],
                         "Total implementation count", "Concrete vs Abstract Implementations per Project")
    fig.tight_layout()
    fig.savefig(out / "abstract_vs_concrete.png", dpi=160)
    plt.close(fig)
    print(f"  Saved abstract_vs_concrete.png")


def impl_total(interface: dict) -> int:
    return len(interface["concreteImplementations"]) + len(interface["abstractImplementations"])


def save_exact_impl_per_project(projects: list[dict], out: Path, n: int,
                                filename: str, color: str, label: str) -> None:
    rows = []
    for p in projects:
        total = len(p["interfaces"])
        if total == 0:
            continue
        count = sum(1 for i in p["interfaces"] if impl_total(i) == n)
        rows.append((p["_project"], count, count / total * 100))

    rows.sort(key=lambda r: r[1], reverse=True)
    names = [r[0] for r in rows]
    counts = [r[1] for r in rows]
    shares = [r[2] for r in rows]

    corpus_total = sum(len(p["interfaces"]) for p in projects)
    corpus_share = sum(counts) / corpus_total * 100 if corpus_total else 0

    fig, (ax_top, ax_bot) = plt.subplots(2, 1, figsize=(18, 9), sharex=True)
    x = np.arange(len(names))

    ax_top.bar(x, counts, color=color, edgecolor="white", linewidth=0.5)
    ax_top.set_title(f"Interfaces with exactly {n} implementation{'' if n == 1 else 's'} "
                     f"({label}) — {sum(counts):,} of {corpus_total:,} interfaces "
                     f"corpus-wide ({corpus_share:.1f}%)")
    ax_top.set_ylabel(f"Interfaces with {n} impl.")
    ax_top.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_top.set_axisbelow(True)

    ax_bot.bar(x, shares, color=color, edgecolor="white", linewidth=0.5)
    ax_bot.axhline(corpus_share, color="#4c566a", linestyle="--", linewidth=1.2,
                   label=f"Corpus-wide rate ({corpus_share:.1f}%)")
    ax_bot.set_ylabel(f"Share of project's interfaces (%)")
    ax_bot.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax_bot.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_bot.set_axisbelow(True)
    ax_bot.legend(loc="upper right")

    ax_bot.set_xticks(x)
    ax_bot.set_xticklabels(names, rotation=90, fontsize=7)
    ax_bot.set_xlim(-0.7, len(names) - 0.3)
    ax_bot.set_xlabel("Project (ordered by raw count, descending)")

    fig.tight_layout()
    fig.savefig(out / filename, dpi=160)
    plt.close(fig)
    print(f"  Saved {filename}")


def save_size_vs_unimplemented(projects: list[dict], out: Path) -> None:
    names, sizes, rates = [], [], []
    for p in projects:
        total = len(p["interfaces"])
        if total == 0:
            continue
        unimplemented = sum(
            1 for i in p["interfaces"]
            if not i["concreteImplementations"] and not i["abstractImplementations"]
        )
        names.append(p["_project"])
        sizes.append(total)
        rates.append(unimplemented / total * 100)

    fig, ax = plt.subplots(figsize=(9, 6))
    ax.scatter(sizes, rates, color=COLORS["scatter"], s=70, zorder=3)
    for name, x, y in zip(names, sizes, rates):
        ax.annotate(name, (x, y), textcoords="offset points", xytext=(6, 3), fontsize=7)
    ax.set_title("Project Size vs Unimplemented Interface Rate")
    ax.set_xlabel("Total unique interfaces")
    ax.set_ylabel("Unimplemented interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.grid(True, linestyle="--", alpha=0.4)
    fig.tight_layout()
    fig.savefig(out / "size_vs_unimplemented.png", dpi=160)
    plt.close(fig)
    print(f"  Saved size_vs_unimplemented.png")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(f"Usage: python {Path(__file__).name} <output-dir>")

    output_dir = Path(sys.argv[1]).resolve()
    if not output_dir.exists():
        sys.exit(f"Error: '{output_dir}' does not exist.")

    projects = load_projects(output_dir)
    if not projects:
        sys.exit(f"No output.json files found under '{output_dir}'.")

    print(f"Loaded {len(projects)} project(s).")

    summary_dir = output_dir / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)

    save_visibility_ratio(projects, summary_dir)
    save_impl_count_distribution(projects, summary_dir)
    save_abstract_vs_concrete(projects, summary_dir)
    save_size_vs_unimplemented(projects, summary_dir)
    save_exact_impl_per_project(projects, summary_dir, 0, "zero_impl_per_project.png",
                                COLORS["zero_impl"], "unimplemented")
    save_exact_impl_per_project(projects, summary_dir, 1, "single_impl_per_project.png",
                                COLORS["single_impl"], "single implementation")

    print(f"\nDone → {summary_dir}")


if __name__ == "__main__":
    main()
