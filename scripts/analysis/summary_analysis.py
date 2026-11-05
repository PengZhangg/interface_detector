import sys
from pathlib import Path

from common import DEFAULT, PALETTE, impl_total, load_projects
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import matplotlib.patches as mpatches
import numpy as np


def grouped_bar_with_pct(ax, names, left_vals, right_vals, left_label, right_label,
                          left_spec, right_spec, ylabel):
    left_arr = np.array(left_vals, dtype=float)
    right_arr = np.array(right_vals, dtype=float)
    totals = left_arr + right_arr
    left_pct = np.where(totals > 0, left_arr / totals * 100, 0)
    right_pct = np.where(totals > 0, right_arr / totals * 100, 0)

    x = np.arange(len(names))
    width = 0.6

    # hide segments that would display as "0%"
    MIN_PCT = 0.5
    left_mask = left_pct >= MIN_PCT
    right_mask = right_pct >= MIN_PCT

    ax.bar(x[left_mask], left_arr[left_mask], width, label=left_label,
                    color=left_spec["fill"], edgecolor=left_spec["edge"], linewidth=1.2)
    ax.bar(x[right_mask], right_arr[right_mask], width,
                    bottom=left_arr[right_mask], label=right_label,
                    color=right_spec["fill"], edgecolor=right_spec["edge"], linewidth=1.2)

    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=45, ha="right", fontsize=8)
    ax.set_xlim(-0.7, len(names) - 0.3)

    # finalize y-limits before reading them back
    ax.margins(y=0.15)
    ax.autoscale_view()
    y_lo, y_top = ax.get_ylim()
    span = y_top - y_lo
    y_nudge = span * 0.01

    # stack both percentage labels above the bar
    for i in range(len(names)):
        cursor = totals[i] + y_nudge
        if left_mask[i]:
            text = f"{left_pct[i]:.0f}%"
            ax.text(x[i], cursor, text, ha="center", va="bottom", rotation=90,
                    fontsize=6, color=left_spec["edge"])
            cursor += span * 0.011 * len(text) + y_nudge
        if right_mask[i]:
            text = f"{right_pct[i]:.0f}%"
            ax.text(x[i], cursor, text, ha="center", va="bottom", rotation=90,
                    fontsize=6, color=right_spec["edge"])

    # explicit handles: a bar container's first patch may have been filtered out
    ax.legend(handles=[
        mpatches.Patch(facecolor=left_spec["fill"], edgecolor=left_spec["edge"],
                       linewidth=1.2, label=left_label),
        mpatches.Patch(facecolor=right_spec["fill"], edgecolor=right_spec["edge"],
                       linewidth=1.2, label=right_label),
    ])


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
    fig.tight_layout()
    fig.savefig(out / "visibility_ratio.png", dpi=160)
    plt.close(fig)
    print("  Saved visibility_ratio.png")


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
    fig.tight_layout()
    fig.savefig(out / "visibility_vs_impl_buckets.png", dpi=160)
    plt.close(fig)
    print("  Saved visibility_vs_impl_buckets.png")


def save_impl_count_distribution(projects: list[dict], out: Path) -> None:
    counts = [impl_total(i) for p in projects for i in p["interfaces"]]

    cap = min(max(counts, default=10), 30)
    filtered = [c for c in counts if c <= cap]
    excluded = len(counts) - len(filtered)

    fig, ax = plt.subplots(figsize=(10, 5))
    ax.hist(filtered, bins=[i - 0.5 for i in range(0, cap + 2)],
            color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
    ax.set_xlim(-0.5, cap + 0.5)
    xlabel = "Number of implementations per interface"
    if excluded:
        xlabel += f"  ({excluded} interface(s) with >{cap} implementations not shown)"
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Number of interfaces")
    ax.set_xticks(range(0, cap + 1))
    fig.tight_layout()
    fig.savefig(out / "impl_count_distribution.png", dpi=160)
    plt.close(fig)
    print("  Saved impl_count_distribution.png")


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
    fig.tight_layout()
    fig.savefig(out / "abstract_vs_concrete.png", dpi=160)
    plt.close(fig)
    print("  Saved abstract_vs_concrete.png")


def save_exact_impl_per_project(projects: list[dict], out: Path, n: int,
                                filename: str, spec: dict) -> None:
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

    ax_top.bar(x, counts, color=spec["fill"], edgecolor=spec["edge"], linewidth=0.8)
    ax_top.set_ylabel(f"Interfaces with {n} impl.")
    ax_top.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_top.set_axisbelow(True)

    ax_bot.bar(x, shares, color=spec["fill"], edgecolor=spec["edge"], linewidth=0.8)
    ax_bot.axhline(corpus_share, color="#555555", linestyle="--", linewidth=1.2,
                   label=f"Corpus-wide rate ({corpus_share:.1f}%)")
    ax_bot.set_ylabel("Share of project's interfaces (%)")
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


def save_exact_impl_pct_per_project(projects: list[dict], out: Path, n: int,
                                    filename: str, spec: dict) -> None:
    rows = []
    for p in projects:
        total = len(p["interfaces"])
        if total == 0:
            continue
        count = sum(1 for i in p["interfaces"] if impl_total(i) == n)
        rows.append((p["_project"], count, total, count / total * 100))

    rows.sort(key=lambda r: r[3], reverse=True)
    names = [r[0] for r in rows]
    shares = [r[3] for r in rows]

    corpus_total = sum(r[2] for r in rows)
    corpus_share = sum(r[1] for r in rows) / corpus_total * 100 if corpus_total else 0

    fig, ax = plt.subplots(figsize=(18, 7))
    x = np.arange(len(names))

    ax.bar(x, shares, color=spec["fill"], edgecolor=spec["edge"], linewidth=0.8)
    ax.axhline(corpus_share, color="#555555", linestyle="--", linewidth=1.2,
               label=f"Corpus-wide rate ({corpus_share:.1f}%)")
    ax.set_ylabel("Share of project's interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax.set_axisbelow(True)
    ax.legend(loc="upper right")

    ax.set_xticks(x)
    ax.set_xticklabels(names, rotation=90, fontsize=7)
    ax.set_xlim(-0.7, len(names) - 0.3)
    ax.set_xlabel("Project (ordered by share, descending)")

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
    ax.scatter(sizes, rates, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8, s=70, zorder=3)
    for name, x, y in zip(names, sizes, rates):
        ax.annotate(name, (x, y), textcoords="offset points", xytext=(6, 3), fontsize=7)
    ax.set_xlabel("Total unique interfaces")
    ax.set_ylabel("Unimplemented interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.grid(True, linestyle="--", alpha=0.4)
    fig.tight_layout()
    fig.savefig(out / "size_vs_unimplemented.png", dpi=160)
    plt.close(fig)
    print("  Saved size_vs_unimplemented.png")


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
    save_visibility_vs_impl_buckets(projects, summary_dir)
    save_impl_count_distribution(projects, summary_dir)
    save_abstract_vs_concrete(projects, summary_dir)
    save_size_vs_unimplemented(projects, summary_dir)
    save_exact_impl_per_project(projects, summary_dir, 0, "zero_impl_per_project.png", DEFAULT)
    save_exact_impl_per_project(projects, summary_dir, 1, "single_impl_per_project.png", DEFAULT)
    save_exact_impl_pct_per_project(projects, summary_dir, 0, "zero_impl_pct_per_project.png", DEFAULT)
    save_exact_impl_pct_per_project(projects, summary_dir, 1, "single_impl_pct_per_project.png", DEFAULT)

    print(f"\nDone → {summary_dir}")


if __name__ == "__main__":
    main()
