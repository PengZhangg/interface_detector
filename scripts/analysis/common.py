import json
import os
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
