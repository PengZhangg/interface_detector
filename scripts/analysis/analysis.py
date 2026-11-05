import json
import sys
from pathlib import Path

import pandas as pd

from common import PALETTE, SCRIPT_DIR
import matplotlib.pyplot as plt

DEFAULT_INPUT = SCRIPT_DIR.parent / "generate_report" / "output.json"
DEFAULT_OUTPUT_DIR = SCRIPT_DIR / "analysis"


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

    totals.plot(kind="bar",
                color=[PALETTE["blue"]["fill"], PALETTE["red"]["fill"]],
                edgecolor=[PALETTE["blue"]["edge"], PALETTE["red"]["edge"]],
                linewidth=1.2)
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

    specs = [PALETTE["blue"], PALETTE["red"], PALETTE["brown"]]
    counts.plot(kind="bar",
                color=[s["fill"] for s in specs],
                edgecolor=[s["edge"] for s in specs],
                linewidth=1.2)
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
