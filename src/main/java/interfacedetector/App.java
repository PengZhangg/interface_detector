package interfacedetector;

import java.util.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) {
        Map<String, List<String>> interfaceImplementations = buildImplementorsMap(Path.of(args[0]));

        System.out.println("Interfaces implemented: " + interfaceImplementations.size());
        for (var entry : interfaceImplementations.entrySet()) {
            System.out.println(entry.getKey() + " interface contains " + entry.getValue().size() + " implementations: "
                    + entry.getValue());
        }
    }

    public static Map<String, List<String>> buildImplementorsMap(Path folder) {
        List<Path> javaFiles;

        try {
            javaFiles = Files.walk(folder)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
        } catch (Exception e) {
            System.err.println("Error walking folder: " + folder + ": " + e.getMessage());
            return new HashMap<>();
        }

        Map<String, List<String>> interfaceImplementations = new HashMap<>();

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
                    if (!interfaceImplementations.containsKey(name)) {
                        interfaceImplementations.put(name, new ArrayList<>());
                    }
                } else {
                    if (decl.isAbstract()) continue;

                    String className = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                    for (var implementedType : decl.getImplementedTypes()) {
                        String ifaceName = implementedType.getNameAsString();
                        if (!interfaceImplementations.containsKey(ifaceName)) {
                            interfaceImplementations.put(ifaceName, new ArrayList<>());
                        }
                        interfaceImplementations.get(ifaceName).add(className);
                    }
                }
            }
        }

        return interfaceImplementations;
    }
}