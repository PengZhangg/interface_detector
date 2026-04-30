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

## Run

```bash
java -jar target/interfaceDetector-1.0-SNAPSHOT.jar <path-to-java-project>
```

`<path-to-java-project>` is the root directory of the Java codebase you want to analyse. The tool walks the directory recursively and parses every `.java` file it finds.

**Usage Example:**

```bash
java -jar target/interfaceDetector-1.0-SNAPSHOT.jar ~/Projects/my-java-repository
```