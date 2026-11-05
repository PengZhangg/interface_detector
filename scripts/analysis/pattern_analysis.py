import csv
import json
import sys
from pathlib import Path

from common import DEFAULT, PALETTE, group_by_project, impl_total, load_projects
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np


def build_graph(project: dict):
    interfaces_by_name = {i["name"]: i for i in project["interfaces"]}
    children_of: dict[str, list[str]] = {name: [] for name in interfaces_by_name}
    for i in project["interfaces"]:
        for parent in i.get("extendedInterfaces", []):
            if parent in children_of:
                children_of[parent].append(i["name"])
    return interfaces_by_name, children_of


def effective_impl_count(name: str, interfaces_by_name: dict, children_of: dict,
                          memo: dict, visiting: set) -> int:
    if name in memo:
        return memo[name]
    if name in visiting:
        return 0
    visiting.add(name)

    total = impl_total(interfaces_by_name[name])
    for child in children_of.get(name, []):
        total += effective_impl_count(child, interfaces_by_name, children_of, memo, visiting)

    visiting.discard(name)
    memo[name] = total
    return total


def analyze_project(project: dict) -> list[dict]:
    interfaces_by_name, children_of = build_graph(project)
    memo: dict[str, int] = {}
    rows = []

    for name, iface in interfaces_by_name.items():
        if impl_total(iface) != 0:
            continue
        if not children_of.get(name):
            continue
        eff = effective_impl_count(name, interfaces_by_name, children_of, memo, set())
        if eff == 0:
            continue
        rows.append({
            "project": project["_project"],
            "interface": name,
            "filePath": iface["filePath"],
            "directChildren": children_of[name],
            "effectiveImplCount": eff,
            "isSingleImpl": eff == 1,
        })

    return rows


def find_impl_chain(name: str, interfaces_by_name: dict, children_of: dict) -> list[str]:
    chain = [name]
    seen = {name}
    current = name
    while True:
        iface = interfaces_by_name[current]
        if impl_total(iface) > 0:
            return chain
        kids = children_of.get(current, [])
        if not kids:
            return chain
        current = kids[0]
        if current in seen:  # defend against any cyclic extends data
            return chain
        seen.add(current)
        chain.append(current)


def save_summary_csv(all_rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(all_rows)

    path = out / "grandparent_pattern_summary.csv"
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "project", "total_interfaces", "grandparent_pattern_count",
            "grandparent_pattern_pct", "grandparent_single_impl_count",
            "grandparent_single_impl_pct",
        ])
        for p in projects:
            name = p["_project"]
            total = len(p["interfaces"])
            rows = by_project.get(name, [])
            gp_count = len(rows)
            single_count = sum(1 for r in rows if r["isSingleImpl"])
            writer.writerow([
                name, total, gp_count,
                f"{gp_count / total * 100:.2f}" if total else "0",
                single_count,
                f"{single_count / total * 100:.2f}" if total else "0",
            ])
    print(f"  Saved {path.name}")


def save_examples_json(all_rows: list[dict], projects_by_name: dict, out: Path, top_n_projects: int = 5,
                        examples_per_project: int = 15) -> None:
    by_project = group_by_project(all_rows)

    ranked = sorted(by_project.items(), key=lambda kv: len(kv[1]), reverse=True)[:top_n_projects]

    examples = {}
    for project_name, rows in ranked:
        project = projects_by_name[project_name]
        interfaces_by_name, children_of = build_graph(project)
        rows_sorted = sorted(rows, key=lambda r: r["effectiveImplCount"])
        picked = rows_sorted[:examples_per_project]
        examples[project_name] = [
            {
                "interface": r["interface"],
                "filePath": r["filePath"],
                "effectiveImplCount": r["effectiveImplCount"],
                "chain": find_impl_chain(r["interface"], interfaces_by_name, children_of),
            }
            for r in picked
        ]

    path = out / "grandparent_pattern_examples.json"
    with open(path, "w") as f:
        json.dump(examples, f, indent=2)
    print(f"  Saved {path.name}")


def analyze_methodless_interfaces(project: dict) -> list[dict]:
    """Interfaces with zero declared methods (marker and constant interfaces)."""
    rows = []
    for iface in project["interfaces"]:
        if iface.get("methodCount", 0) != 0:
            continue
        rows.append({
            "project": project["_project"],
            "interface": iface["name"],
            "filePath": iface["filePath"],
            "implCount": impl_total(iface),
        })
    return rows


def save_methodless_summary_csv(all_rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(all_rows)

    path = out / "methodless_interface_summary.csv"
    with open(path, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "project", "total_interfaces", "methodless_interface_count",
            "methodless_interface_pct",
        ])
        for p in projects:
            name = p["_project"]
            total = len(p["interfaces"])
            rows = by_project.get(name, [])
            count = len(rows)
            writer.writerow([
                name, total, count,
                f"{count / total * 100:.2f}" if total else "0",
            ])
    print(f"  Saved {path.name}")


def save_methodless_examples_json(all_rows: list[dict], out: Path, top_n_projects: int = 5,
                                   examples_per_project: int = 15) -> None:
    by_project = group_by_project(all_rows)

    ranked = sorted(by_project.items(), key=lambda kv: len(kv[1]), reverse=True)[:top_n_projects]

    examples = {}
    for project_name, rows in ranked:
        rows_sorted = sorted(rows, key=lambda r: r["interface"])
        picked = rows_sorted[:examples_per_project]
        examples[project_name] = [
            {
                "interface": r["interface"],
                "filePath": r["filePath"],
                "implCount": r["implCount"],
            }
            for r in picked
        ]

    path = out / "methodless_interface_examples.json"
    with open(path, "w") as f:
        json.dump(examples, f, indent=2)
    print(f"  Saved {path.name}")


def save_methodless_bar_chart(all_rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(all_rows)

    rows = []
    for p in projects:
        name = p["_project"]
        total = len(p["interfaces"])
        if total == 0:
            continue
        count = len(by_project.get(name, []))
        rows.append((name, count, count / total * 100))

    rows.sort(key=lambda r: r[1], reverse=True)
    rows = [r for r in rows if r[1] > 0]
    names = [r[0] for r in rows]
    counts = [r[1] for r in rows]
    shares = [r[2] for r in rows]

    fig, (ax_top, ax_bot) = plt.subplots(2, 1, figsize=(18, 9), sharex=True)
    x = np.arange(len(names))

    ax_top.bar(x, counts, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
    ax_top.set_ylabel("Interface count")
    ax_top.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_top.set_axisbelow(True)

    ax_bot.bar(x, shares, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
    ax_bot.set_ylabel("Share of project's interfaces (%)")
    ax_bot.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax_bot.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_bot.set_axisbelow(True)

    ax_bot.set_xticks(x)
    ax_bot.set_xticklabels(names, rotation=90, fontsize=7)
    ax_bot.set_xlim(-0.7, len(names) - 0.3)
    ax_bot.set_xlabel("Project (ordered by methodless-interface count, descending)")

    fig.tight_layout()
    fig.savefig(out / "methodless_interface_per_project.png", dpi=160)
    plt.close(fig)
    print("  Saved methodless_interface_per_project.png")


def save_bar_chart(all_rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(all_rows)

    rows = []
    for p in projects:
        name = p["_project"]
        total = len(p["interfaces"])
        if total == 0:
            continue
        prows = by_project.get(name, [])
        gp_count = len(prows)
        single_count = sum(1 for r in prows if r["isSingleImpl"])
        rows.append((name, gp_count, single_count, gp_count / total * 100))

    rows.sort(key=lambda r: r[1], reverse=True)
    rows = [r for r in rows if r[1] > 0]
    names = [r[0] for r in rows]
    gp_counts = [r[1] for r in rows]
    single_counts = [r[2] for r in rows]

    fig, (ax_top, ax_bot) = plt.subplots(2, 1, figsize=(18, 9), sharex=True)
    x = np.arange(len(names))

    ax_top.bar(x, gp_counts, color=PALETTE["blue"]["fill"], edgecolor=PALETTE["blue"]["edge"],
               linewidth=0.8, label="Grandparent pattern (any effective impl count)")
    ax_top.bar(x, single_counts, color=PALETTE["red"]["fill"], edgecolor=PALETTE["red"]["edge"],
               linewidth=0.8, label="...of which, effective impl count == 1")
    ax_top.set_ylabel("Interface count")
    ax_top.legend(loc="upper right")
    ax_top.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_top.set_axisbelow(True)

    shares = [r[3] for r in rows]
    ax_bot.bar(x, shares, color=DEFAULT["fill"], edgecolor=DEFAULT["edge"], linewidth=0.8)
    ax_bot.set_ylabel("Share of project's interfaces (%)")
    ax_bot.yaxis.set_major_formatter(mticker.PercentFormatter())
    ax_bot.grid(True, axis="y", linestyle="--", alpha=0.4)
    ax_bot.set_axisbelow(True)

    ax_bot.set_xticks(x)
    ax_bot.set_xticklabels(names, rotation=90, fontsize=7)
    ax_bot.set_xlim(-0.7, len(names) - 0.3)
    ax_bot.set_xlabel("Project (ordered by grandparent-pattern count, descending)")

    fig.tight_layout()
    fig.savefig(out / "grandparent_pattern_per_project.png", dpi=160)
    plt.close(fig)
    print("  Saved grandparent_pattern_per_project.png")


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

    all_rows = []
    for p in projects:
        all_rows.extend(analyze_project(p))

    print(f"Found {len(all_rows)} interface(s) showing the grandparent pattern "
          f"({sum(1 for r in all_rows if r['isSingleImpl'])} with effective impl count == 1).")

    summary_dir = output_dir / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)

    projects_by_name = {p["_project"]: p for p in projects}
    save_summary_csv(all_rows, projects, summary_dir)
    save_examples_json(all_rows, projects_by_name, summary_dir)
    save_bar_chart(all_rows, projects, summary_dir)

    methodless_rows = []
    for p in projects:
        methodless_rows.extend(analyze_methodless_interfaces(p))

    print(f"Found {len(methodless_rows)} interface(s) with zero declared methods "
          "(marker/constant interfaces).")

    save_methodless_summary_csv(methodless_rows, projects, summary_dir)
    save_methodless_examples_json(methodless_rows, summary_dir)
    save_methodless_bar_chart(methodless_rows, projects, summary_dir)

    print(f"\nDone -> {summary_dir}")


if __name__ == "__main__":
    main()
