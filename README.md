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

The Python script executes the full pipeline and writes all output into a folder named `<ProjectName>Output/` next to the input folder:

```text
<ProjectName>Output/
├── output.json        # raw analysis data
├── report.html        # HTML report
├── report.css         # stylesheet for the report
├── concrete_vs_abstract.png
├── implementation_buckets.png
└── errors.txt         # parse errors, if any
```

### Batch mode

Point `run.py` at a directory of Java projects and it will process each one:

```bash
python run.py <path-to-directory-of-projects>
```

Each subdirectory that contains `.java` files gets its own `<ProjectName>Output/` folder.
