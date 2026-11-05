package interfacedetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import tools.jackson.databind.ObjectMapper;

public class App {
    public static void main(String[] args) throws Exception {
        Path scanDir = Path.of(args[0]);
        Path outputFile = args.length > 1 ? Path.of(args[1]) : Path.of("output.json");

        Map<String, List<ImplementationEntry>> concreteImpl = new HashMap<>();
        Map<String, List<ImplementationEntry>> abstractImpl = new HashMap<>();
        Map<String, String> interfaceFilePaths = new HashMap<>();
        Map<String, String> interfaceVisibilities = new HashMap<>();
        Map<String, List<String>> extendedInterfaces = new HashMap<>();
        Map<String, Integer> methodCounts = new HashMap<>();

        buildImplementorsMaps(scanDir, concreteImpl, abstractImpl, interfaceFilePaths, interfaceVisibilities,
                extendedInterfaces, methodCounts);

        Set<String> allInterfaces = new HashSet<>();
        allInterfaces.addAll(concreteImpl.keySet());
        allInterfaces.addAll(abstractImpl.keySet());

        List<Map<String, Object>> interfaces = new ArrayList<>();
        for (String name : allInterfaces) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("filePath", interfaceFilePaths.getOrDefault(name, "unknown"));
            entry.put("visibility", interfaceVisibilities.getOrDefault(name, "unknown"));
            entry.put("concreteImplementations", concreteImpl.getOrDefault(name, List.of()));
            entry.put("abstractImplementations", abstractImpl.getOrDefault(name, List.of()));
            entry.put("extendedInterfaces", extendedInterfaces.getOrDefault(name, List.of()));
            entry.put("methodCount", methodCounts.getOrDefault(name, 0));
            interfaces.add(entry);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedDirectory", scanDir.toAbsolutePath().toString());
        report.put("totalUniqueInterfaces", allInterfaces.size());
        report.put("interfaces", interfaces);

        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), report);
        System.out.println("Total unique interfaces detected: " + allInterfaces.size());
        System.out.println("Output written to: " + outputFile.toAbsolutePath());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl) {
        buildImplementorsMaps(folder, concreteImpl, abstractImpl, new HashMap<>(), new HashMap<>());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl,
            Map<String, String> interfaceFilePaths,
            Map<String, String> interfaceVisibilities) {
        buildImplementorsMaps(folder, concreteImpl, abstractImpl, interfaceFilePaths, interfaceVisibilities,
                new HashMap<>(), new HashMap<>());
    }

    public static void buildImplementorsMaps(Path folder,
            Map<String, List<ImplementationEntry>> concreteImpl,
            Map<String, List<ImplementationEntry>> abstractImpl,
            Map<String, String> interfaceFilePaths,
            Map<String, String> interfaceVisibilities,
            Map<String, List<String>> extendedInterfaces,
            Map<String, Integer> methodCounts) {
        StaticJavaParser.setConfiguration(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));

        List<Path> javaFiles;
        try (var walk = Files.walk(folder)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        } catch (Exception e) {
            System.err.println("Error walking folder: " + folder + ": " + e.getMessage());
            return;
        }

        // Interfaces are keyed by fully-qualified name; simpleNameIndex maps the
        // names written in implements/extends clauses back to those keys.
        Map<String, List<String>> simpleNameIndex = new HashMap<>();
        Map<String, String> interfacePackages = new HashMap<>();

        // first pass: collect all interfaces
        for (Path file : javaFiles) {
            CompilationUnit cu = parse(file);
            if (cu == null)
                continue;
            String filePath = pathOf(cu);
            String pkg = packageOf(cu);

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface())
                    continue;
                String name = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
                concreteImpl.putIfAbsent(name, new ArrayList<>());
                abstractImpl.putIfAbsent(name, new ArrayList<>());
                interfaceFilePaths.putIfAbsent(name, filePath);
                interfaceVisibilities.putIfAbsent(name, getVisibility(decl));
                interfacePackages.putIfAbsent(name, pkg);
                simpleNameIndex.computeIfAbsent(decl.getNameAsString(), k -> new ArrayList<>()).add(name);
                List<String> parents = new ArrayList<>();
                for (ClassOrInterfaceType extendedType : decl.getExtendedTypes())
                    parents.add(extendedType.getNameAsString());
                extendedInterfaces.put(name, parents);
                methodCounts.put(name, decl.getMethods().size());
            }
        }

        // resolve "extends" names to qualified interface keys
        for (String child : new ArrayList<>(extendedInterfaces.keySet())) {
            String childPkg = interfacePackages.getOrDefault(child, "");
            String childFile = interfaceFilePaths.getOrDefault(child, "unknown");
            List<String> resolvedParents = new ArrayList<>();
            for (String raw : extendedInterfaces.get(child)) {
                String resolved = resolveInterfaceName(raw, childPkg, childFile,
                        simpleNameIndex, interfacePackages, interfaceFilePaths, child);
                resolvedParents.add(resolved != null ? resolved : raw);
            }
            extendedInterfaces.put(child, resolvedParents);
        }

        // second pass: record implementing classes, enums and records
        for (Path file : javaFiles) {
            CompilationUnit cu = parse(file);
            if (cu == null)
                continue;
            String filePath = pathOf(cu);
            String pkg = packageOf(cu);

            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface())
                    addImplementations(decl, decl.isAbstract() ? abstractImpl : concreteImpl, pkg, filePath,
                            simpleNameIndex, interfacePackages, interfaceFilePaths);
            }
            // enums and records can never be abstract
            List<TypeDeclaration<?>> alwaysConcrete = new ArrayList<>(cu.findAll(EnumDeclaration.class));
            alwaysConcrete.addAll(cu.findAll(RecordDeclaration.class));
            for (TypeDeclaration<?> decl : alwaysConcrete)
                addImplementations(decl, concreteImpl, pkg, filePath,
                        simpleNameIndex, interfacePackages, interfaceFilePaths);
        }
    }

    private static void addImplementations(TypeDeclaration<?> decl,
            Map<String, List<ImplementationEntry>> targetMap, String pkg, String filePath,
            Map<String, List<String>> simpleNameIndex, Map<String, String> interfacePackages,
            Map<String, String> interfaceFilePaths) {
        String className = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
        for (ClassOrInterfaceType implementedType : ((NodeWithImplements<?>) decl).getImplementedTypes()) {
            String resolved = resolveInterfaceName(implementedType.getNameAsString(), pkg, filePath,
                    simpleNameIndex, interfacePackages, interfaceFilePaths, null);
            List<ImplementationEntry> implementors = resolved == null ? null : targetMap.get(resolved);
            if (implementors != null)
                implementors.add(new ImplementationEntry(className, filePath));
        }
    }

    /**
     * Maps a name written in an implements/extends clause to its qualified
     * interface key. Ambiguous names fall back to same file, then same
     * package, else null. {@code excludeSelf} keeps an interface from
     * resolving to itself when it extends a same-named interface.
     */
    private static String resolveInterfaceName(String written, String implementorPackage, String implementorFile,
            Map<String, List<String>> simpleNameIndex, Map<String, String> interfacePackages,
            Map<String, String> interfaceFilePaths, String excludeSelf) {
        String lastSegment = written.substring(written.lastIndexOf('.') + 1);
        List<String> candidates = simpleNameIndex.getOrDefault(lastSegment, List.of()).stream()
                .filter(c -> !c.equals(excludeSelf))
                .toList();
        if (candidates.isEmpty())
            return null;
        if (candidates.size() == 1)
            return candidates.get(0);

        if (written.contains(".")) {
            List<String> suffixMatches = candidates.stream()
                    .filter(c -> c.equals(written) || c.endsWith("." + written))
                    .toList();
            if (suffixMatches.size() == 1)
                return suffixMatches.get(0);
        }

        List<String> sameFile = candidates.stream()
                .filter(c -> implementorFile.equals(interfaceFilePaths.get(c)))
                .toList();
        if (sameFile.size() == 1)
            return sameFile.get(0);

        List<String> samePackage = candidates.stream()
                .filter(c -> implementorPackage.equals(interfacePackages.getOrDefault(c, "")))
                .toList();
        return samePackage.size() == 1 ? samePackage.get(0) : null;
    }

    private static String getVisibility(ClassOrInterfaceDeclaration decl) {
        if (decl.isPublic())
            return "public";
        if (decl.isPrivate())
            return "private";
        if (decl.isProtected())
            return "protected";
        // members of an interface are implicitly public (JLS 9.5)
        boolean nestedInInterface = decl.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::isInterface)
                .orElse(false);
        return nestedInInterface ? "public" : "package-private";
    }

    private static CompilationUnit parse(Path file) {
        try {
            return StaticJavaParser.parse(file);
        } catch (Exception e) {
            System.err.println("Error parsing file: " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    private static String pathOf(CompilationUnit cu) {
        return cu.getStorage().map(s -> s.getPath().toAbsolutePath().toString()).orElse("unknown");
    }
}
