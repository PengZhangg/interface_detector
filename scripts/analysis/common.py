import json
import os
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

# must be set before matplotlib is imported
os.environ.setdefault("MPLCONFIGDIR", str(SCRIPT_DIR / ".matplotlib-cache"))

import matplotlib.pyplot as plt

plt.style.use(str(SCRIPT_DIR / "paper.mplstyle"))

PALETTE = {
    "blue":  {"fill": "#aab8e8", "edge": "#3b4cca"},
    "red":   {"fill": "#f4b6b6", "edge": "#d62728"},
    "brown": {"fill": "#e3d1b4", "edge": "#8c6239"},
}
DEFAULT = PALETTE["blue"]


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


def impl_total(interface: dict) -> int:
    return len(interface["concreteImplementations"]) + len(interface["abstractImplementations"])


def group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    by_project: dict[str, list[dict]] = {}
    for r in rows:
        by_project.setdefault(r["project"], []).append(r)
    return by_project


def load_corpus_from_argv(script: str) -> tuple[list[dict], Path]:
    """Reads <output-dir> from argv; returns its projects and the summary/ folder to write into."""
    if len(sys.argv) != 2:
        sys.exit(f"Usage: python {Path(script).name} <output-dir>")
    output_dir = Path(sys.argv[1]).resolve()
    if not output_dir.exists():
        sys.exit(f"Error: '{output_dir}' does not exist.")
    projects = load_projects(output_dir)
    if not projects:
        sys.exit(f"No output.json files found under '{output_dir}'.")
    print(f"Loaded {len(projects)} project(s).")
    summary_dir = output_dir / "summary"
    summary_dir.mkdir(parents=True, exist_ok=True)
    return projects, summary_dir


def pct_str(count: int, total: int) -> str:
    return f"{count / total * 100:.2f}" if total else "0"


def children_of(interfaces: list[dict]) -> dict[str, list[str]]:
    """Maps each interface name to the interfaces that directly extend it."""
    children: dict[str, list[str]] = {i["name"]: [] for i in interfaces}
    for i in interfaces:
        for parent in i.get("extendedInterfaces", []):
            if parent in children:
                children[parent].append(i["name"])
    return children


def has_implemented_descendant(name: str, children: dict[str, list[str]], implemented: set[str]) -> bool:
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
