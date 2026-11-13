"""Corpus-wide charts over every project's output.json.

Usage: python summary_analysis.py <output-dir>
"""
from pathlib import Path

from charts import ProjectRow, grouped_bar_with_pct, save, save_count_and_share_per_project, \
    save_share_per_project
from common import DEFAULT, PALETTE, impl_total, load_corpus_from_argv
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np


def save_visibility_ratio(projects: list[dict], out: Path) -> None:
    names, pub_counts, pkg_counts = [], [], []
    for p in projects:
        names.append(p["_project"])
        pub_counts.append(sum(1 for i in p["interfaces"] if i["visibility"] == "public"))
        pkg_counts.append(sum(1 for i in p["interfaces"] if i["visibility"] == "package-private"))

    fig, ax = plt.subplots(figsize=(max(14, len(names) * 0.3), 6))
    grouped_bar_with_pct(ax, names, pub_counts, pkg_counts,
                         "Public", "Package-private",
                         PALETTE["blue"], PALETTE["red"],
                         "Interface count")
    save(fig, out, "visibility_ratio.png")


def save_visibility_vs_impl_buckets(projects: list[dict], out: Path) -> None:
    """Share of public vs package-private interfaces with zero, one or many implementations."""
    buckets = {"public": {"zero": 0, "one": 0, "many": 0},
               "package-private": {"zero": 0, "one": 0, "many": 0}}
    for p in projects:
        for i in p["interfaces"]:
            vis = i["visibility"]
            if vis not in buckets:
                continue
            total = impl_total(i)
            key = "zero" if total == 0 else "one" if total == 1 else "many"
            buckets[vis][key] += 1

    groups = ["public", "package-private"]
    labels = ["Public", "Package-private"]
    bucket_names = ["zero", "one", "many"]
    bucket_labels = ["Zero implementations", "One implementation", "Many implementations (2+)"]
    specs = [PALETTE["red"], PALETTE["brown"], PALETTE["blue"]]

    totals = [sum(buckets[g].values()) for g in groups]
    pcts = {
        bname: [buckets[g][bname] / t * 100 if t else 0 for g, t in zip(groups, totals)]
        for bname in bucket_names
    }

    for g, t in zip(groups, totals):
        counts_str = ", ".join(f"{bn}={buckets[g][bn]} ({buckets[g][bn] / t * 100:.1f}%)" for bn in bucket_names)
        print(f"    {g} (n={t}): {counts_str}")

    x = np.arange(len(groups))
    width = 0.5
    fig, ax = plt.subplots(figsize=(6, 6))
    bottom = np.zeros(len(groups))
    for bname, blabel, spec in zip(bucket_names, bucket_labels, specs):
        vals = np.array(pcts[bname])
        bars = ax.bar(x, vals, width, bottom=bottom, label=blabel,
                      color=spec["fill"], edgecolor=spec["edge"], linewidth=1.2)
        for bar, v in zip(bars, vals):
            if v > 3:
                ax.text(bar.get_x() + bar.get_width() / 2, bar.get_y() + v / 2,
                        f"{v:.0f}%", ha="center", va="center", fontsize=9)
        bottom += vals

    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_ylabel("Share of interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.set_ylim(0, 100)
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.1), ncol=1)
    save(fig, out, "visibility_vs_impl_buckets.png")


def save_abstract_vs_concrete(projects: list[dict], out: Path) -> None:
    names, concrete_totals, abstract_totals = [], [], []
    for p in projects:
        names.append(p["_project"])
        concrete_totals.append(sum(len(i["concreteImplementations"]) for i in p["interfaces"]))
        abstract_totals.append(sum(len(i["abstractImplementations"]) for i in p["interfaces"]))

    fig, ax = plt.subplots(figsize=(max(14, len(names) * 0.3), 6))
    grouped_bar_with_pct(ax, names, concrete_totals, abstract_totals,
                         "Concrete", "Abstract",
                         PALETTE["blue"], PALETTE["red"],
                         "Total implementation count")
    save(fig, out, "abstract_vs_concrete.png")


def exact_impl_rows(projects: list[dict], n: int) -> list[ProjectRow]:
    """Per project: how many interfaces have exactly n implementations."""
    return [(p["_project"], sum(1 for i in p["interfaces"] if impl_total(i) == n), len(p["interfaces"]))
            for p in projects]


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
    ax.scatter(sizes, rates, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8, s=70, zorder=3)
    for name, x, y in zip(names, sizes, rates):
        ax.annotate(name, (x, y), textcoords="offset points", xytext=(6, 3), fontsize=7)
    ax.set_xlabel("Total unique interfaces")
    ax.set_ylabel("Unimplemented interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.grid(True, linestyle="--", alpha=0.4)
    save(fig, out, "size_vs_unimplemented.png")


def main() -> None:
    projects, summary_dir = load_corpus_from_argv(__file__)

    save_visibility_ratio(projects, summary_dir)
    save_visibility_vs_impl_buckets(projects, summary_dir)
    save_abstract_vs_concrete(projects, summary_dir)
    save_size_vs_unimplemented(projects, summary_dir)
    for n, label in ((0, "zero"), (1, "single")):
        rows = exact_impl_rows(projects, n)
        save_count_and_share_per_project(rows, summary_dir, f"{label}_impl_per_project.png",
                                         f"Interfaces with {n} impl.",
                                         "Project (ordered by raw count, descending)", show_corpus_rate=True)
        save_share_per_project(rows, summary_dir, f"{label}_impl_pct_per_project.png")

    print(f"\nDone → {summary_dir}")


if __name__ == "__main__":
    main()
