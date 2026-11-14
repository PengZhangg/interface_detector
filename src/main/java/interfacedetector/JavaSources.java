package interfacedetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;

final class JavaSources {

    private JavaSources() {
    }

    static JavaParser newParser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));
    }

    static List<Path> list(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    static Optional<CompilationUnit> parse(JavaParser parser, Path file, boolean strict, boolean report) {
        Optional<CompilationUnit> unit;
        String reason = "";
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            unit = strict && !result.isSuccessful() ? Optional.empty() : result.getResult();
            if (!result.getProblems().isEmpty())
                reason = ": " + result.getProblem(0).getVerboseMessage();
        } catch (Exception e) {
            unit = Optional.empty();
            reason = ": " + e.getMessage();
        }
        if (unit.isEmpty() && report)
            System.err.println("Error parsing file: " + file + reason);
        return unit;
    }

    static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    static String pathOf(CompilationUnit cu) {
        return cu.getStorage().map(s -> s.getPath().toAbsolutePath().toString()).orElse("unknown");
    }
}
