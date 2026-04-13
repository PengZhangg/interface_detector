package interfacedetector;

import java.util.*;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) throws Exception {
        Path folder = Path.of(args[0]);

        List<Path> javaFiles = Files.walk(folder)
                .filter(p -> p.toString().endsWith(".java"))
                .toList();

        for (Path file : javaFiles) {
            CompilationUnit cu = StaticJavaParser.parse(file);  // parse each file
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                System.out.println(decl.getNameAsString() + " — interface: " + decl.isInterface());
            }
        }
    }
}