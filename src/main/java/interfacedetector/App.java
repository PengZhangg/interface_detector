package interfacedetector;

import java.util.*;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) {
        Map<String, List<String>> concreteImpl = new HashMap<>();
        Map<String, List<String>> abstractImpl = new HashMap<>();

        buildImplementorsMaps(Path.of(args[0]), concreteImpl, abstractImpl);

        System.out.println("=== Concrete Implementations ===");
        for (var entry : concreteImpl.entrySet()) {
            if (!entry.getValue().isEmpty())
                System.out.println(entry.getKey() + ": " + entry.getValue());
        }

        System.out.println("\n=== Abstract Implementations ===");
        for (var entry : abstractImpl.entrySet()) {
            if (!entry.getValue().isEmpty())
                System.out.println(entry.getKey() + ": " + entry.getValue());
        }
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<String>> concreteImpl, Map<String, List<String>> abstractImpl) {
        StaticJavaParser.setConfiguration(new ParserConfiguration()
                .setLanguageLevel(LanguageLevel.JAVA_21));

        List<Path> javaFiles;

        try {
            javaFiles = Files.walk(folder)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (Exception e) {
            System.err.println("Error walking folder: " + folder + ": " + e.getMessage());
            return;
        }

        for (Path file : javaFiles) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(file);
            } catch (Exception e) {
                System.err.println("Error parsing file: " + file + ": " + e.getMessage());
                continue;
            }
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface()) {
                    String name = decl.getNameAsString();
                    concreteImpl.putIfAbsent(name, new ArrayList<>());
                    abstractImpl.putIfAbsent(name, new ArrayList<>());
                } else {
                    String className = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                    Map<String, List<String>> targetMap = decl.isAbstract() ? abstractImpl : concreteImpl;

                    for (var implementedType : decl.getImplementedTypes()) {
                        String ifaceName = implementedType.getNameAsString();
                        targetMap.computeIfAbsent(ifaceName, k -> new ArrayList<>()).add(className);
                    }
                }
            }
            for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
                String className = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                for (var implementedType : decl.getImplementedTypes()) {
                    String ifaceName = implementedType.getNameAsString();
                    concreteImpl.computeIfAbsent(ifaceName, k -> new ArrayList<>()).add(className);
                }
            }
        }
    }
}