"""Per-project summary of zero-implementation interfaces used as parameter types.

Usage: python parameter_analysis.py <output-dir>

<output-dir> is the batch output folder written by run.py; each
<Project>Output/ in it needs output.json and zero_impl_params.csv (from
interfacedetector.ParameterUsage). Writes, into <output-dir>/summary/:
  zero_impl_params_per_project.csv  one row per project
  zero_impl_params_per_project.md   the same as a markdown table
  zero_impl_params_per_project.png  stacked bar chart
"""
import csv
import json
import sys
from pathlib import Path

from common import PALETTE, impl_total
from summary_analysis import grouped_bar_with_pct
import matplotlib.pyplot as plt

COLUMNS = [
    "project",
    "zeroImplInterfaces",
    "usedAsParamType",
    "usedAsParamTypePct",
    "methodsWithParam",       # distinct methods/constructors/records with >= 1 such parameter
    "params",                 # parameter declarations of such a type
    "methodsInInterfaces",    # ...of which declared inside an interface (incl. the interface itself)
    "methodsInClasses",       # ...of which declared in a class, enum or record
    # disjoint groups, checked in this order:
    "viaSubInterface",        # a sub-interface of it has implementations (grandparent pattern)
    "anonymousClass",         # otherwise, the project creates one with new Foo() { ... }
    "lambdaCompatible",       # otherwise, a lambda can implement it (see ParameterUsage.Iface)
    "noImplementationFound",  # neither: implemented outside the project, or dead
]


def implemented_via_sub_interface(name: str, children: dict, implemented: set) -> bool:
    """True if any interface extending `name`, directly or further down, has an implementation."""
    stack, seen = list(children.get(name, [])), set()
    while stack:
        child = stack.pop()
        if child in seen:
            continue
        seen.add(child)
        if child in implemented:
            return True
        stack.extend(children.get(child, []))
    return False


def summarise(project_dir: Path) -> dict | None:
    json_path = project_dir / "output.json"
    csv_path = project_dir / "zero_impl_params.csv"
    if not (json_path.exists() and csv_path.exists()):
        return None

    with open(json_path) as f:
        interfaces = json.load(f)["interfaces"]
    zero = {i["name"]: i for i in interfaces if impl_total(i) == 0}
    with open(csv_path, newline="") as f:
        rows = list(csv.DictReader(f))

    used = {r["interface"] for r in rows}
    lambda_ok = {r["interface"] for r in rows if r["lambdaCompatible"] == "true"}
    implemented = {i["name"] for i in interfaces if impl_total(i) > 0}
    children = {}
    for i in interfaces:
        for parent in i["extendedInterfaces"]:
            children.setdefault(parent, []).append(i["name"])
    via_sub = {n for n in used if implemented_via_sub_interface(n, children, implemented)}
    anonymous = {r["interface"] for r in rows if int(r["anonymousClasses"]) > 0}
    via_anonymous = (used & anonymous) - via_sub
    via_lambda = (used & lambda_ok) - via_sub - via_anonymous
    methods = {}  # key -> declaring kind
    for r in rows:
        methods[(r["file"], r["memberLine"], r["member"])] = r["declaringKind"]
    in_interfaces = sum(1 for k in methods.values() if k in ("self", "interface"))

    return {
        "project": project_dir.name.removesuffix("Output"),
        "zeroImplInterfaces": len(zero),
        "usedAsParamType": len(used),
        "usedAsParamTypePct": round(100 * len(used) / len(zero), 1) if zero else 0.0,
        "methodsWithParam": len(methods),
        "params": len(rows),
        "methodsInInterfaces": in_interfaces,
        "methodsInClasses": len(methods) - in_interfaces,
        "viaSubInterface": len(via_sub),
        "anonymousClass": len(via_anonymous),
        "lambdaCompatible": len(via_lambda),
        "noImplementationFound": len(used) - len(via_sub) - len(via_anonymous) - len(via_lambda),
    }


def save_chart(stats: list[dict], out: Path) -> None:
    names = [s["project"] for s in stats]
    used = [s["usedAsParamType"] for s in stats]
    unused = [s["zeroImplInterfaces"] - s["usedAsParamType"] for s in stats]

    fig, ax = plt.subplots(figsize=(max(14, len(names) * 0.3), 6))
    grouped_bar_with_pct(ax, names, used, unused,
                         "Used as a parameter type", "Never a parameter type",
                         PALETTE["blue"], PALETTE["red"],
                         "Zero-implementation interfaces")
    fig.tight_layout()
    fig.savefig(out / "zero_impl_params_per_project.png", dpi=160)
    plt.close(fig)
    print("  Saved zero_impl_params_per_project.png")


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    output_dir = Path(sys.argv[1])
    stats = [s for d in sorted(output_dir.iterdir()) if d.is_dir() and (s := summarise(d))]
    if not stats:
        print("No projects with zero_impl_params.csv found.")
        sys.exit(1)

    totals = {c: sum(s[c] for s in stats) for c in COLUMNS[1:] if c != "usedAsParamTypePct"}
    totals["project"] = "TOTAL"
    totals["usedAsParamTypePct"] = round(
        100 * totals["usedAsParamType"] / totals["zeroImplInterfaces"], 1
    ) if totals["zeroImplInterfaces"] else 0.0

    summary_dir = output_dir / "summary"
    summary_dir.mkdir(exist_ok=True)
    with open(summary_dir / "zero_impl_params_per_project.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(stats + [totals])
    with open(summary_dir / "zero_impl_params_per_project.md", "w") as f:
        f.write("| " + " | ".join(COLUMNS) + " |\n")
        f.write("|" + "---|" * len(COLUMNS) + "\n")
        for s in stats + [totals]:
            f.write("| " + " | ".join(str(s[c]) for c in COLUMNS) + " |\n")
    save_chart(stats, summary_dir)
    print(f"Parameter summary → {summary_dir}")


if __name__ == "__main__":
    main()
