"""Grandparent pattern (zero-impl interfaces whose sub-interfaces are implemented)
and methodless (marker/constant) interfaces.

Usage: python pattern_analysis.py <output-dir>
"""
import csv
import json
from pathlib import Path

from charts import save_count_and_share_per_project
from common import children_of as build_children, group_by_project, impl_total, load_corpus_from_argv, pct_str


def build_graph(project: dict):
    return {i["name"]: i for i in project["interfaces"]}, build_children(project["interfaces"])


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
                pct_str(gp_count, total),
                single_count,
                pct_str(single_count, total),
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
                pct_str(count, total),
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
    rows = [(p["_project"], len(by_project.get(p["_project"], [])), len(p["interfaces"])) for p in projects]
    save_count_and_share_per_project(rows, out, "methodless_interface_per_project.png", "Interface count",
                                     "Project (ordered by methodless-interface count, descending)")


def save_bar_chart(all_rows: list[dict], projects: list[dict], out: Path) -> None:
    by_project = group_by_project(all_rows)
    rows = [(p["_project"], len(by_project.get(p["_project"], [])), len(p["interfaces"])) for p in projects]
    single = {name: sum(1 for r in prows if r["isSingleImpl"]) for name, prows in by_project.items()}
    save_count_and_share_per_project(rows, out, "grandparent_pattern_per_project.png", "Interface count",
                                     "Project (ordered by grandparent-pattern count, descending)",
                                     overlay=(single, "Grandparent pattern (any effective impl count)",
                                              "...of which, effective impl count == 1"))


def main() -> None:
    projects, summary_dir = load_corpus_from_argv(__file__)

    all_rows = []
    for p in projects:
        all_rows.extend(analyze_project(p))

    print(f"Found {len(all_rows)} interface(s) showing the grandparent pattern "
          f"({sum(1 for r in all_rows if r['isSingleImpl'])} with effective impl count == 1).")

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
