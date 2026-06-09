#!/usr/bin/env python3

import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
JAR = SCRIPT_DIR / "target" / "interfaceDetector-1.0-SNAPSHOT.jar"
REPORT_SCRIPT = SCRIPT_DIR / "scripts" / "generate_report" / "generate_report.py"
REPORT_CSS_SRC = SCRIPT_DIR / "scripts" / "generate_report" / "report.css"
ANALYSIS_SCRIPT = SCRIPT_DIR / "scripts" / "analysis" / "analysis.py"
SUMMARY_SCRIPT = SCRIPT_DIR / "scripts" / "analysis" / "summary_analysis.py"

_VENV_PYTHON = SCRIPT_DIR / ".venv" / "bin" / "python"
PYTHON = str(_VENV_PYTHON) if _VENV_PYTHON.exists() else sys.executable


def has_java_sources(directory: Path, max_depth: int = 4) -> bool:
    for _ in directory.rglob("*.java"):
        return True
    return False


def process_project(input_dir: Path, output_base: Path) -> None:
    project_name = input_dir.name
    output_dir = output_base / f"{project_name}Output"
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"==> Processing: {project_name}")

    # step 1: execute jar, capture errors and store in errors.txt
    json_out = output_dir / "output.json"
    errors_out = output_dir / "errors.txt"
    result = subprocess.run(
        ["java", "-jar", str(JAR), str(input_dir), str(json_out)],
        check=True,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.stderr.strip():
        errors_out.write_text(result.stderr)
        print(f"    [1/3] JSON  → {json_out} (errors → {errors_out})")
    else:
        print(f"    [1/3] JSON  → {json_out}")

    # step 2: generate HTML report
    html_out = output_dir / "report.html"
    subprocess.run(
        [PYTHON, str(REPORT_SCRIPT), str(json_out), str(html_out)],
        check=True,
    )
    print(f"    [2/3] HTML  → {html_out}")

    # step 3: copy css
    shutil.copy2(REPORT_CSS_SRC, output_dir / "report.css")

    # step 4: generate graphs
    subprocess.run(
        [PYTHON, str(ANALYSIS_SCRIPT), str(json_out), str(output_dir)],
        check=True,
    )
    graphs = sorted(output_dir.glob("*.png"))
    for g in graphs:
        print(f"    [3/3] Graph → {g}")

    print(f"    Done! Output → {output_dir}\n")


def run_summary(output_base: Path) -> None:
    print("=" * 60)
    print("Running summary analysis across all projects...")
    subprocess.run(
        [PYTHON, str(SUMMARY_SCRIPT), str(output_base)],
        check=True,
    )
    print(f"Summary output → {output_base / 'summary'}\n")


def main() -> None:
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)

    target = Path(sys.argv[1]).resolve()
    if not target.exists():
        print(f"Error: '{target}' does not exist.")
        sys.exit(1)

    projects = [
        d for d in sorted(target.iterdir()) if d.is_dir() and has_java_sources(d)
    ]

    if projects:
        output_base = target / "output"
        output_base.mkdir(parents=True, exist_ok=True)
        print(f"Found {len(projects)} project(s) in '{target.name}'. Output → {output_base}\n")
        for i, project in enumerate(projects, 1):
            print(f"[{i}/{len(projects)}] Starting: {project.name}")
            process_project(project, output_base)
        run_summary(output_base)
        return

    if has_java_sources(target):
        output_base = target.parent / "output"
        output_base.mkdir(parents=True, exist_ok=True)
        process_project(target, output_base)
        return

    print(f"No Java source files found in '{target}' or its immediate subdirectories.")
    sys.exit(1)


if __name__ == "__main__":
    main()
