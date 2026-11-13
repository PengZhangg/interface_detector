"""Chart building blocks shared by the analysis scripts.

Per-project charts take rows of (project, count, total), where `count` is the
number of interfaces with some property and `total` is the project's interface
count.
"""
from pathlib import Path

from common import DEFAULT, PALETTE
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

ProjectRow = tuple[str, int, int]  # (project, count, total)


def save(fig, out: Path, filename: str) -> None:
    fig.tight_layout()
    fig.savefig(out / filename, dpi=160)
    plt.close(fig)
    print(f"  Saved {filename}")


def share(count: int, total: int) -> float:
    return count / total * 100 if total else 0


def bars(ax, x, values, spec: dict = DEFAULT, **kwargs):
    return ax.bar(x, values, color=spec["fill"], edgecolor=spec["edge"], linewidth=0.8, **kwargs)


def y_grid(ax) -> None:
    ax.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax.set_axisbelow(True)


def corpus_line(ax, corpus_share: float) -> None:
    ax.axhline(corpus_share, color="#555555", linestyle="--", linewidth=1.2,
               label=f"Corpus-wide rate ({corpus_share:.1f}%)")


def share_axis(ax, with_legend: bool) -> None:
    ax.set_ylabel("Share of project's interfaces (%)")
    ax.yaxis.set_major_formatter(mticker.PercentFormatter())
    y_grid(ax)
    if with_legend:
        ax.legend(loc="upper right")


def project_x_axis(ax, names: list[str], xlabel: str) -> None:
    ax.set_xticks(np.arange(len(names)))
    ax.set_xticklabels(names, rotation=90, fontsize=7)
    ax.set_xlim(-0.7, len(names) - 0.3)
    ax.set_xlabel(xlabel)


def save_share_per_project(rows: list[ProjectRow], out: Path, filename: str) -> None:
    """One bar per project with its share, highest first, plus the corpus-wide rate."""
    rows = sorted(rows, key=lambda r: share(r[1], r[2]), reverse=True)
    names = [r[0] for r in rows]
    corpus_share = share(sum(r[1] for r in rows), sum(r[2] for r in rows))

    fig, ax = plt.subplots(figsize=(18, 7))
    bars(ax, np.arange(len(names)), [share(r[1], r[2]) for r in rows])
    corpus_line(ax, corpus_share)
    share_axis(ax, with_legend=True)
    project_x_axis(ax, names, "Project (ordered by share, descending)")
    save(fig, out, filename)


def save_count_and_share_per_project(rows: list[ProjectRow], out: Path, filename: str, count_label: str,
                                     xlabel: str, show_corpus_rate: bool = False,
                                     overlay: tuple[dict[str, int], str, str] | None = None) -> None:
    """Counts on top, shares below, ordered by count.

    `overlay` is (counts by project, label for the main bars, label for the
    overlay) and draws a red subset on top of the main count bars.
    """
    rows = sorted(rows, key=lambda r: r[1], reverse=True)
    names = [r[0] for r in rows]
    x = np.arange(len(names))

    fig, (ax_top, ax_bot) = plt.subplots(2, 1, figsize=(18, 9), sharex=True)

    if overlay:
        subset, main_label, subset_label = overlay
        bars(ax_top, x, [r[1] for r in rows], PALETTE["blue"], label=main_label)
        bars(ax_top, x, [subset.get(r[0], 0) for r in rows], PALETTE["red"], label=subset_label)
    else:
        bars(ax_top, x, [r[1] for r in rows])
    ax_top.set_ylabel(count_label)
    if overlay:
        ax_top.legend(loc="upper right")
    y_grid(ax_top)

    bars(ax_bot, x, [share(r[1], r[2]) for r in rows])
    if show_corpus_rate:
        corpus_line(ax_bot, share(sum(r[1] for r in rows), sum(r[2] for r in rows)))
    share_axis(ax_bot, with_legend=show_corpus_rate)
    project_x_axis(ax_bot, names, xlabel)
    save(fig, out, filename)


def grouped_bar_with_pct(ax, names, left_vals, right_vals, left_label, right_label,
                         left_spec, right_spec, ylabel):
    """Stacked two-part bar per project, largest first, with each part's % written above."""
    left_arr = np.array(left_vals, dtype=float)
    right_arr = np.array(right_vals, dtype=float)
    totals = left_arr + right_arr

    order = np.argsort(-totals, kind="stable")
    names = [names[i] for i in order]
    left_arr, right_arr, totals = left_arr[order], right_arr[order], totals[order]

    left_pct = np.where(totals > 0, left_arr / totals * 100, 0)
    right_pct = np.where(totals > 0, right_arr / totals * 100, 0)

    x = np.arange(len(names))
    width = 0.6

    # draw every non-zero segment; empty ones are skipped so no outline is drawn
    left_bar = left_arr > 0
    right_bar = right_arr > 0
    ax.bar(x[left_bar], left_arr[left_bar], width, label=left_label,
           color=left_spec["fill"], edgecolor=left_spec["edge"], linewidth=1.2)
    ax.bar(x[right_bar], right_arr[right_bar], width,
           bottom=left_arr[right_bar], label=right_label,
           color=right_spec["fill"], edgecolor=right_spec["edge"], linewidth=1.2)

    # hide labels that would display as "0%"
    min_pct = 0.5
    left_mask = left_pct >= min_pct
    right_mask = right_pct >= min_pct

    ax.set_ylabel(ylabel)
    ax.set_xlabel("Project (ordered by total, descending)")
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
