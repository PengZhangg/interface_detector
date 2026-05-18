import html
import json
import sys
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_INPUT = SCRIPT_DIR / "output.json"
DEFAULT_OUTPUT = SCRIPT_DIR / "report.html"
TEMPLATE = SCRIPT_DIR / "report_template.html"


# convert the local path into a browser readable file:// link
def make_path_link(file_path):
    if not file_path or file_path == "unknown":
        return html.escape(file_path or "unknown")

    href = Path(file_path).resolve().as_uri()
    label = html.escape(file_path)
    return f'<a href="{html.escape(href, quote=True)}">{label}</a>'


# format each implementation as class name followed by clickable file path
def format_implementations(implementations):
    if not implementations:
        return "-"

    return "<br>".join(
        f"{html.escape(item['className'])}<br><small>{make_path_link(item['filePath'])}</small>"
        for item in implementations
    )

# build rows for every interface
def build_interface_row(interface):
    visibility = html.escape(interface.get("visibility", "unknown"))
    interface_cell = (
        f"{html.escape(interface['name'])}"
        f" <span class=\"visibility-label\">{visibility}</span>"
        f"<br><small>{make_path_link(interface['filePath'])}</small>"
    )

    return f"""
    <tr>
      <td>{interface_cell}</td>
      <td>{format_implementations(interface["concreteImplementations"])}</td>
      <td>{format_implementations(interface["abstractImplementations"])}</td>
    </tr>
    """

# convert interface obj from JSON into one HTML row, load into template
def build_report(data):
    rows = "".join(build_interface_row(interface) for interface in data["interfaces"])

    with open(TEMPLATE) as f:
        page = f.read()

    return (
        page.replace("{{SCANNED_DIRECTORY}}", make_path_link(data["scannedDirectory"]))
        .replace("{{TOTAL_UNIQUE_INTERFACES}}", str(data["totalUniqueInterfaces"]))
        .replace("{{ROWS}}", rows)
    )


def main():
    input_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_INPUT
    output_path = Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUTPUT

    with open(input_path) as f:
        data = json.load(f)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w") as f:
        f.write(build_report(data))


if __name__ == "__main__":
    main()
