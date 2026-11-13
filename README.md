# Interface Detector

Static analysis tool for examining interface usage patterns in open-source Java projects.
Built with JavaParser to analyse how interfaces are implemented and referenced across a corpus of Java programs, measuring dead interfaces (zero implementations), singleton interfaces (one implementation), and interfaces that provide genuine polymorphic value.

## Prerequisites

- Java 21+
- Maven 3.6+
- Python 3.14+ with a virtual environment

## Setup

Build the JAR and set up the Python virtual environment once:

```bash
mvn package
python3 -m venv .venv
.venv/bin/pip install -r scripts/analysis/requirements.txt
```

## Usage

```bash
python run.py <path-to-java-project>
```

This runs the full pipeline and writes everything to `output/<ProjectName>Output/` next to the input folder:

```text
<ProjectName>Output/
├── output.json            # interfaces, implementations, visibility, extends, method counts
├── zero_impl_params.csv   # parameters typed with a zero-implementation interface
├── report.html            # HTML report (+ report.css)
└── errors.txt             # parse errors, if any
```

### Batch mode

Point `run.py` at a directory of Java projects and it will process each one:

```bash
python run.py <path-to-directory-of-projects>
```

Each subdirectory that contains `.java` files gets its own `output/<ProjectName>Output/` folder. Then the corpus-wide analyses run, writing charts and tables to `output/summary/`:

| Script | Covers |
| --- | --- |
| `scripts/analysis/summary_analysis.py` | visibility, abstract vs concrete, zero/single implementation rates |
| `scripts/analysis/parameter_analysis.py` | zero-implementation interfaces used as parameter types |
| `scripts/analysis/multi_impl_analysis.py` | interfaces with 2+ implementations |
| `scripts/analysis/pattern_analysis.py` | grandparent pattern, marker/constant interfaces |

Each one can also be rerun on its own against an existing output folder:

```bash
.venv/bin/python scripts/analysis/<script>.py <output-dir>
```
