package interfacedetector;

import java.util.*;
import tools.jackson.databind.ObjectMapper; // Jackson's main entry point for reading/writing JSON
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {
    public static void main(String[] args) throws Exception {
        Path scanDir = Path.of(args[0]);
        Path outputFile = args.length > 1 ? Path.of(args[1]) : Path.of("output.json");

        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        Map<String, String> interfaceFilePaths = new HashMap<>();
        Map<String, String> interfaceVisibilities = new HashMap<>();

        buildImplementorsMaps(scanDir, concreteImpl, abstractImpl, interfaceFilePaths, interfaceVisibilities);
        
        Set<String> allInterfaces = new HashSet<>();
        allInterfaces.addAll(concreteImpl.keySet());
        allInterfaces.addAll(abstractImpl.keySet());

        List<Map<String, Object>> interfaces = new ArrayList<>();
        for (String interfaceName : allInterfaces) {
            Map<String, Object> interfaceEntry = new LinkedHashMap<>();
            interfaceEntry.put("name", interfaceName);
            interfaceEntry.put("filePath", interfaceFilePaths.getOrDefault(interfaceName, "unknown"));
            interfaceEntry.put("visibility", interfaceVisibilities.getOrDefault(interfaceName, "unknown"));
            interfaceEntry.put("concreteImplementations", concreteImpl.getOrDefault(interfaceName, Collections.emptyList()));
            interfaceEntry.put("abstractImplementations", abstractImpl.getOrDefault(interfaceName, Collections.emptyList()));
            interfaces.add(interfaceEntry);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedDirectory", scanDir.toAbsolutePath().toString());
        report.put("totalUniqueInterfaces", allInterfaces.size());
        report.put("interfaces", interfaces);

        ObjectMapper mapper = new ObjectMapper(); 
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), report); 
        System.out.println("Total unique interfaces detected: " + allInterfaces.size());
        System.out.println("Output written to: " + outputFile.toAbsolutePath());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl) {
        buildImplementorsMaps(folder, concreteImpl, abstractImpl, new HashMap<>());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl,
            Map<String, String> interfaceFilePaths) {
        buildImplementorsMaps(folder, concreteImpl, abstractImpl, interfaceFilePaths, new HashMap<>());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl,
            Map<String, String> interfaceFilePaths,
            Map<String, String> interfaceVisibilities) {
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

        // first pass: collect all interfaces as keys
        for (Path file : javaFiles) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(file);
            } catch (Exception e) {
                System.err.println("Error parsing file: " + file + ": " + e.getMessage());
                continue;
            }

            String filePath = cu.getStorage()
                    .map(s -> s.getPath().toAbsolutePath().toString())
                    .orElse("unknown");

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (decl.isInterface()) {
                    String name = decl.getNameAsString();
                    concreteImpl.putIfAbsent(name, new ArrayList<>());
                    abstractImpl.putIfAbsent(name, new ArrayList<>());
                    interfaceFilePaths.putIfAbsent(name, filePath);
                    interfaceVisibilities.putIfAbsent(name, getVisibility(decl));
                }
            }
        }

        // second pass: append implementing classes to existing interface keys
        for (Path file : javaFiles) {
            CompilationUnit cu;
            try {
                cu = StaticJavaParser.parse(file);
            } catch (Exception e) {
                System.err.println("Error parsing file: " + file + ": " + e.getMessage());
                continue;
            }

            String filePath = cu.getStorage()
                    .map(s -> s.getPath().toAbsolutePath().toString())
                    .orElse("unknown");

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface()) {
                    String className = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                    Map<String, List<ImplementationEntry>> targetMap = decl.isAbstract() ? abstractImpl : concreteImpl;
                    for (var implementedType : decl.getImplementedTypes()) {
                        String implementedInterfaceName = implementedType.getNameAsString();
                        List<ImplementationEntry> implementors = targetMap.get(implementedInterfaceName);
                        if (implementors != null)
                            implementors.add(new ImplementationEntry(className, filePath));
                    }
                }
            }

            for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class)) {
                String className = decl.getFullyQualifiedName().orElse(decl.getNameAsString());
                for (var implementedType : decl.getImplementedTypes()) {
                    String implementedInterfaceName = implementedType.getNameAsString();
                    List<ImplementationEntry> implementors = concreteImpl.get(implementedInterfaceName);
                    if (implementors != null)
                        implementors.add(new ImplementationEntry(className, filePath));
                }
            }
        }
    }

    private static String getVisibility(ClassOrInterfaceDeclaration decl) {
        if (decl.isPublic())
            return "public";
        if (decl.isPrivate())
            return "private";
        if (decl.isProtected())
            return "protected";
        return "package-private";
    }
}
