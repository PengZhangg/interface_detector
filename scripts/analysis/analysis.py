import json
import os
import sys
from pathlib import Path

import pandas as pd

SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_INPUT = SCRIPT_DIR.parent / "generate_report" / "output.json"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR / "analysis"

# use specified directory to store cache
os.environ.setdefault("MPLCONFIGDIR", str(SCRIPT_DIR / ".matplotlib-cache"))

import matplotlib.pyplot as plt


def build_dataframe(data):
    rows = []
    for interface in data["interfaces"]:
        concrete_count = len(interface["concreteImplementations"])
        abstract_count = len(interface["abstractImplementations"])
        total_count = concrete_count + abstract_count

        if total_count == 0:
            bucket = "0 implementations"
        elif total_count == 1:
            bucket = "1 implementation"
        else:
            bucket = "many implementations"

        rows.append(
            {
                "concrete": concrete_count,
                "abstract": abstract_count,
                "bucket": bucket,
            }
        )

    return pd.DataFrame(rows)


def save_concrete_vs_abstract(df, output_dir):
    totals = pd.Series(
        {
            "Concrete": df["concrete"].sum(),
            "Abstract": df["abstract"].sum(),
        }
    )

    totals.plot(kind="bar", color=["#ebcb8b", "#a3be8c"])
    plt.title("Concrete vs Abstract Implementations")
    plt.ylabel("Implementation count")
    plt.xticks(rotation=0)
    plt.tight_layout()
    plt.savefig(output_dir / "concrete_vs_abstract.png", dpi=160)
    plt.close()


def save_implementation_buckets(df, output_dir):
    counts = (
        df["bucket"]
        .value_counts()
        .reindex(
            ["0 implementations", "1 implementation", "many implementations"],
            fill_value=0,
        )
    )

    counts.plot(kind="bar", color=["#d08770", "#ebcb8b", "#a3be8c"])
    plt.title("Interfaces by Implementation Count")
    plt.ylabel("Number of interfaces")
    plt.xticks(rotation=0)
    plt.tight_layout()
    plt.savefig(output_dir / "implementation_buckets.png", dpi=160)
    plt.close()


def main():
    input_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_INPUT
    output_dir = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT_DIR

    with open(input_path) as f:
        data = json.load(f)

    output_dir.mkdir(parents=True, exist_ok=True)
    df = build_dataframe(data)
    save_concrete_vs_abstract(df, output_dir)
    save_implementation_buckets(df, output_dir)


if __name__ == "__main__":
    main()
