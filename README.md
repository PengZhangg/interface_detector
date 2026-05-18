# Interface Detector
Static analysis tool for examining interface usage patterns in open-source Java projects. 
Built with JavaParser to analyse how interfaces are implemented and referenced across a corpus of Java programs - 
measuring dead interfaces (zero implementations), singleton interfaces (one implementation) and interfaces that provide genuine polymorphic value.

## Prerequisites

- Java 21+
- Maven 3.6+

## Compile

```bash
mvn package
```

This produces a self-contained JAR at `target/interfaceDetector-1.0-SNAPSHOT.jar`.

## Generate JSON Output

```bash
java -jar target/interfaceDetector-1.0-SNAPSHOT.jar <path-to-java-project> scripts/generate_report/output.json
```

`<path-to-java-project>` is the root directory of the Java codebase you want to analyse. The tool walks the directory recursively and parses every `.java` file it finds.

**Usage Example:**

```bash
java -jar target/interfaceDetector-1.0-SNAPSHOT.jar ~/Projects/my-java-repository scripts/generate_report/output.json
```

For larger projects, you may need to increase the Java heap size:

```bash
java -Xmx4g -jar target/interfaceDetector-1.0-SNAPSHOT.jar ~/Projects/my-java-repository scripts/generate_report/output.json
```

## Generate HTML Report

The JSON output can be converted into a nicer HTML page using the report generator:

```bash
python scripts/generate_report/generate_report.py scripts/generate_report/output.json scripts/generate_report/report.html
```

This creates:

```text
scripts/generate_report/report.html
```

## Generate Basic Graphs

Install the Python dependencies once:

```bash
pip install -r scripts/analysis/requirements.txt
```

Then generate graphs from the JSON output:

```bash
python scripts/analysis/analysis.py scripts/generate_report/output.json scripts/analysis/analysis
```

This creates basic graph images such as:

```text
scripts/analysis/analysis/concrete_vs_abstract.png
scripts/analysis/analysis/implementation_buckets.png
```

## Full Workflow

```bash
mvn package
java -Xmx4g -jar target/interfaceDetector-1.0-SNAPSHOT.jar ~/Projects/my-java-repository scripts/generate_report/output.json
python scripts/generate_report/generate_report.py scripts/generate_report/output.json scripts/generate_report/report.html
python scripts/analysis/analysis.py scripts/generate_report/output.json scripts/analysis/analysis
```
