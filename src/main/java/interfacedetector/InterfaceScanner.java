package interfacedetector;

import java.nio.file.Path;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

/**
 * Finds every interface in a project and the classes, enums and records that
 * implement it. Works on syntax only, so names in implements/extends clauses
 * are matched to interfaces heuristically (see {@link #resolve}).
 */
public final class InterfaceScanner {

    private final JavaParser parser = JavaSources.newParser();
    /** Keyed by fully-qualified name. */
    private final Map<String, InterfaceInfo> interfaces = new HashMap<>();
    /** Simple name to fully-qualified names, one entry per declaration. */
    private final Map<String, List<String>> simpleNameIndex = new HashMap<>();

    private InterfaceScanner() {
    }

    /** Scans every .java file under {@code folder}; files that fail to parse are reported and skipped. */
    public static Map<String, InterfaceInfo> scan(Path folder) {
        List<Path> files;
        try {
            files = JavaSources.list(folder);
        } catch (Exception e) {
            System.err.println("Error walking folder: " + folder + ": " + e.getMessage());
            return Map.of();
        }
        InterfaceScanner scanner = new InterfaceScanner();
        // every interface must be known before implements clauses can be resolved
        for (Path file : files)
            JavaSources.parse(scanner.parser, file, true, true).ifPresent(scanner::collectInterfaces);
        scanner.resolveParents();
        for (Path file : files)
            JavaSources.parse(scanner.parser, file, true, false).ifPresent(scanner::collectImplementations);
        return scanner.interfaces;
    }

    private void collectInterfaces(CompilationUnit cu) {
        String filePath = JavaSources.pathOf(cu);
        String pkg = JavaSources.packageOf(cu);
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface())
                continue;
            String name = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
            // if a name is declared twice, the first file and visibility are kept but the
            // parents and method count come from the last declaration
            InterfaceInfo info = interfaces.computeIfAbsent(name,
                    n -> new InterfaceInfo(n, filePath, visibilityOf(decl), pkg));
            simpleNameIndex.computeIfAbsent(decl.getNameAsString(), k -> new ArrayList<>()).add(name);
            info.setExtendedInterfaces(decl.getExtendedTypes().stream().map(ClassOrInterfaceType::getNameAsString)
                    .toList());
            info.setMethodCount(decl.getMethods().size());
        }
    }

    /** Replaces each "extends" name as written with the qualified name it refers to, when resolvable. */
    private void resolveParents() {
        for (InterfaceInfo child : interfaces.values()) {
            List<String> resolved = new ArrayList<>();
            for (String written : child.extendedInterfaces()) {
                String parent = resolve(written, child.packageName(), child.filePath(), child.name());
                resolved.add(parent != null ? parent : written);
            }
            child.setExtendedInterfaces(resolved);
        }
    }

    private void collectImplementations(CompilationUnit cu) {
        String filePath = JavaSources.pathOf(cu);
        String pkg = JavaSources.packageOf(cu);
        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface())
                addImplementations(decl, decl.getImplementedTypes(), decl.isAbstract(), pkg, filePath);
        }
        // enums and records can never be abstract
        for (EnumDeclaration decl : cu.findAll(EnumDeclaration.class))
            addImplementations(decl, decl.getImplementedTypes(), false, pkg, filePath);
        for (RecordDeclaration decl : cu.findAll(RecordDeclaration.class))
            addImplementations(decl, decl.getImplementedTypes(), false, pkg, filePath);
    }

    private void addImplementations(TypeDeclaration<?> decl, List<ClassOrInterfaceType> implementedTypes,
            boolean isAbstract, String pkg, String filePath) {
        String className = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
        for (ClassOrInterfaceType implemented : implementedTypes) {
            String resolved = resolve(implemented.getNameAsString(), pkg, filePath, null);
            InterfaceInfo target = resolved == null ? null : interfaces.get(resolved);
            if (target == null)
                continue;
            (isAbstract ? target.abstractImplementations() : target.concreteImplementations())
                    .add(new ImplementationEntry(className, filePath));
        }
    }

    /**
     * Maps a name written in an implements/extends clause to its qualified
     * interface name. An ambiguous name falls back to a match in the same
     * file, then the same package, else null. {@code excludeSelf} keeps an
     * interface from resolving to itself when it extends a same-named interface.
     */
    private String resolve(String written, String fromPackage, String fromFile, String excludeSelf) {
        String simple = written.substring(written.lastIndexOf('.') + 1);
        List<String> candidates = simpleNameIndex.getOrDefault(simple, List.of()).stream()
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
                .filter(c -> fromFile.equals(interfaces.get(c).filePath()))
                .toList();
        if (sameFile.size() == 1)
            return sameFile.get(0);

        List<String> samePackage = candidates.stream()
                .filter(c -> fromPackage.equals(interfaces.get(c).packageName()))
                .toList();
        return samePackage.size() == 1 ? samePackage.get(0) : null;
    }

    private static String visibilityOf(ClassOrInterfaceDeclaration decl) {
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
}
