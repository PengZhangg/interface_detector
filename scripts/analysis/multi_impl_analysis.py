import csv
import sys
from pathlib import Path

from common import DEFAULT, PALETTE, group_by_project, impl_total, load_projects
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

DOMINANT_THRESHOLD = 10  # interfaces with >= this many implementations are "dominant hubs"


def collect_multi_impl(projects: list[dict]) -> list[dict]:
    """One row per interface with more than 1 implementation."""
    rows = []
    for p in projects:
        for i in p["interfaces"]:
            total = impl_total(i)
            if total <= 1:
                continue
            rows.append({
                "project": p["_project"],
                "interface": i["name"],
                "filePath": i["filePath"],
                "concrete": len(i["concreteImplementations"]),
                "abstract": len(i["abstractImplementations"]),
                "total": total,
            })
    return rows


def save_frequency_summary_csv(rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(rows)

    path = out / "multi_impl_summary.csv"
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "project", "total_interfaces", "multi_impl_count", "multi_impl_pct",
            "avg_impl_count", "median_impl_count", "max_impl_count",
            "dominant_hub_count",
        ])
        for p in projects:
            name = p["_project"]
            total = len(p["interfaces"])
            prows = by_project.get(name, [])
            counts = [r["total"] for r in prows]
            writer.writerow([
                name, total, len(prows),
                f"{len(prows) / total * 100:.2f}" if total else "0",
                f"{np.mean(counts):.2f}" if counts else "0",
                f"{np.median(counts):.1f}" if counts else "0",
                max(counts, default=0),
                sum(1 for c in counts if c >= DOMINANT_THRESHOLD),
            ])
    print(f"  Saved {path.name}")


def save_top_hubs_csv(rows: list[dict], out: Path, top_n: int = 100) -> None:
    ranked = sorted(rows, key=lambda r: r["total"], reverse=True)[:top_n]
    path = out / "multi_impl_top_hubs.csv"
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["project", "interface", "filePath", "concrete", "abstract", "total"])
        for r in ranked:
            writer.writerow([r["project"], r["interface"], r["filePath"],
                              r["concrete"], r["abstract"], r["total"]])
    print(f"  Saved {path.name}")


def save_multi_impl_distribution(rows: list[dict], out: Path) -> None:
    """Histogram of implementation counts, restricted to interfaces with >1 impl."""
    counts = [r["total"] for r in rows]
    cap = min(max(counts, default=10), 50)
    filtered = [c for c in counts if c <= cap]
    excluded = len(counts) - len(filtered)

    fig, ax = plt.subplots(figsize=(11, 5))
    ax.hist(filtered, bins=[i - 0.5 for i in range(2, cap + 2)],
            color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
    ax.axvline(DOMINANT_THRESHOLD - 0.5, color="#555555", linestyle="--",
                linewidth=1.2, label=f"Dominant-hub threshold ({DOMINANT_THRESHOLD}+ impls)")
    ax.set_xlim(1.5, cap + 0.5)
    xlabel = "Number of implementations per interface"
    if excluded:
        xlabel += f"  ({excluded} interface(s) with >{cap} implementations not shown)"
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Number of interfaces")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "multi_impl_distribution.png", dpi=160)
    plt.close(fig)
    print("  Saved multi_impl_distribution.png")


def save_multi_impl_distribution_logy(rows: list[dict], out: Path) -> None:
    """Log-log degree distribution of implementation counts (cf. Baxter et al. 2006)."""
    counts = [r["total"] for r in rows]
    freq: dict[int, int] = {}
    for c in counts:
        freq[c] = freq.get(c, 0) + 1
    ks = sorted(freq)
    ys = [freq[k] for k in ks]

    fig, ax = plt.subplots(figsize=(7, 6))
    ax.scatter(ks, ys, s=22, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"],
               linewidth=0.8, zorder=3)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.axvline(DOMINANT_THRESHOLD, color="#555555", linestyle="--",
                linewidth=1.2, label=f"Dominant-hub threshold ({DOMINANT_THRESHOLD}+ impls)")
    ax.set_xlabel("Number of implementations per interface (k, log scale)")
    ax.set_ylabel("Number of interfaces with k implementations (log scale)")
    ax.grid(True, which="both", linewidth=0.4, alpha=0.5)
    ax.annotate(
        "cf. in-degree power law, Baxter et al. (2006)",
        xy=(0.97, 0.95), xycoords="axes fraction", ha="right", va="top",
        fontsize=8, style="italic", color="#555555",
    )
    ax.legend()
    fig.tight_layout()
    fig.savefig(out / "multi_impl_distribution_logy.png", dpi=160)
    plt.close(fig)
    print("  Saved multi_impl_distribution_logy.png")


def save_concentration_curve(rows: list[dict], out: Path) -> None:
    """Lorenz-style curve: share of all implementations owned by the top X% of interfaces."""
    counts = sorted((r["total"] for r in rows), reverse=True)
    n = len(counts)
    total_impls = sum(counts)
    cum = np.cumsum(counts) / total_impls * 100
    x = np.arange(1, n + 1) / n * 100

    fig, ax = plt.subplots(figsize=(8, 6))
    ax.plot(x, cum, color=DEFAULT["edge"], linewidth=2)
    ax.plot([0, 100], [0, 100], color="#555555", linestyle="--", linewidth=1, label="Perfect equality")
    ax.set_xlabel("Interfaces (ranked by implementation count, highest first) — %")
    ax.set_ylabel("Cumulative share of all implementations — %")
    ax.xaxis.set_major_formatter(mticker.PercentFormatter())
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax.grid(True, linestyle="--", alpha=0.4)
    ax.legend(loc="lower right")

    for pct in (1, 5, 10, 20):
        k = max(1, int(round(n * pct / 100)))
        share = cum[k - 1]
        ax.annotate(f"top {pct}% -> {share:.0f}% of impls",
                    (pct, share), textcoords="offset points", xytext=(8, -10), fontsize=7.5)

    fig.tight_layout()
    fig.savefig(out / "multi_impl_concentration.png", dpi=160)
    plt.close(fig)
    print("  Saved multi_impl_concentration.png")


def save_bucketed_breakdown(rows: list[dict], out: Path) -> None:
    """How many multi-impl interfaces fall into 2-3, 4-9, 10+ buckets."""
    buckets = [("2-3", lambda c: 2 <= c <= 3),
               ("4-9", lambda c: 4 <= c <= 9),
               (f"{DOMINANT_THRESHOLD}+", lambda c: c >= DOMINANT_THRESHOLD)]
    counts = [r["total"] for r in rows]
    labels = [b[0] for b in buckets]
    values = [sum(1 for c in counts if pred(c)) for _, pred in buckets]
    total = len(counts)
    pcts = [v / total * 100 if total else 0 for v in values]

    specs = [PALETTE["blue"], PALETTE["red"], PALETTE["brown"]]
    fig, ax = plt.subplots(figsize=(6, 5))
    bars = ax.bar(labels, values, color=[s["fill"] for s in specs],
                   edgecolor=[s["edge"] for s in specs], linewidth=1.2)
    for bar, pct, spec in zip(bars, pcts, specs):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height(),
                f"{pct:.1f}%", ha="center", va="bottom", fontsize=9, color=spec["edge"])
    ax.set_xlabel("Implementation count bucket")
    ax.set_ylabel("Number of interfaces")
    fig.tight_layout()
    fig.savefig(out / "multi_impl_buckets.png", dpi=160)
    plt.close(fig)
    print("  Saved multi_impl_buckets.png")


def save_per_project_rate(rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(rows)

    data = []
    for p in projects:
        name = p["_project"]
        total = len(p["interfaces"])
        if total == 0:
            continue
        prows = by_project.get(name, [])
        data.append((name, len(prows), len(prows) / total * 100))

    data.sort(key=lambda d: d[2], reverse=True)
    names = [d[0] for d in data]
    pcts = [d[2] for d in data]

    corpus_total = sum(len(p["interfaces"]) for p in projects)
    corpus_share = len(rows) / corpus_total * 100 if corpus_total else 0

    fig, ax = plt.subplots(figsize=(18, 7))
    x = np.arange(len(names))
    ax.bar(x, pcts, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
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
    fig.savefig(out / "multi_impl_pct_per_project.png", dpi=160)
    plt.close(fig)
    print("  Saved multi_impl_pct_per_project.png")


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

    rows = collect_multi_impl(projects)
    counts = [r["total"] for r in rows]
    corpus_total = sum(len(p["interfaces"]) for p in projects)

    print(f"Found {len(rows)} interface(s) with >1 implementation "
          f"({len(rows) / corpus_total * 100:.1f}% of {corpus_total:,} interfaces corpus-wide).")
    if counts:
        print(f"  mean={np.mean(counts):.2f}  median={np.median(counts):.1f}  "
              f"max={max(counts)}  dominant hubs (>={DOMINANT_THRESHOLD} impls)="
              f"{sum(1 for c in counts if c >= DOMINANT_THRESHOLD)}")

    summary_dir = output_dir / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)

    save_frequency_summary_csv(rows, projects, summary_dir)
    save_top_hubs_csv(rows, summary_dir)
    save_multi_impl_distribution(rows, summary_dir)
    save_multi_impl_distribution_logy(rows, summary_dir)
    save_concentration_curve(rows, summary_dir)
    save_bucketed_breakdown(rows, summary_dir)
    save_per_project_rate(rows, projects, summary_dir)

    print(f"\nDone -> {summary_dir}")


if __name__ == "__main__":
    main()
