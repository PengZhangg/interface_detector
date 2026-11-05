package interfacedetector;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists method, constructor and record parameters whose type is a
 * zero-implementation interface from an App output.json.
 *
 * Usage: ParameterUsage <scanDir> <output.json> <zero_impl_params.csv>
 */
public class ParameterUsage {

    record Iface(String fqn, String simple, String pkg, String file, boolean topLevel,
            int abstractMethods, boolean extendsOthers, boolean functionalAnnotation) {

        /** True if annotated @FunctionalInterface, or with exactly one own abstract method and no parents. */
        boolean lambdaCompatible() {
            return functionalAnnotation || (abstractMethods == 1 && !extendsOthers);
        }
    }

    record ParamRow(Iface iface, String declaringType, String declaringKind, String member,
            String memberKind, int memberLine, String paramName, String paramType, boolean direct, String file, int line) {
    }

    public static void main(String[] args) throws Exception {
        Path scanDir = Path.of(args[0]);
        Path outputJson = Path.of(args[1]);
        Path csvOut = Path.of(args[2]);

        Set<String> zeroImpl = new HashSet<>();
        JsonNode report = new ObjectMapper().readTree(outputJson.toFile());
        for (JsonNode i : report.get("interfaces")) {
            if (i.get("concreteImplementations").isEmpty() && i.get("abstractImplementations").isEmpty())
                zeroImpl.add(i.get("name").asString());
        }

        Map<String, Integer> anonymousClasses = new HashMap<>();
        List<ParamRow> rows = analyze(scanDir, zeroImpl, anonymousClasses);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(csvOut))) {
            out.println("interface,declaringType,declaringKind,member,memberKind,memberLine,paramName,paramType,direct,file,line,"
                    + "abstractMethods,extendsOthers,functionalAnnotation,lambdaCompatible,anonymousClasses");
            for (ParamRow r : rows) {
                Iface i = r.iface();
                out.println(String.join(",", csv(i.fqn()), csv(r.declaringType()), r.declaringKind(),
                        csv(r.member()), r.memberKind(), String.valueOf(r.memberLine()), csv(r.paramName()), csv(r.paramType()),
                        String.valueOf(r.direct()), csv(r.file()), String.valueOf(r.line()),
                        String.valueOf(i.abstractMethods()), String.valueOf(i.extendsOthers()),
                        String.valueOf(i.functionalAnnotation()), String.valueOf(i.lambdaCompatible()),
                        String.valueOf(anonymousClasses.getOrDefault(i.fqn(), 0))));
            }
        }
        System.out.println("Zero-implementation parameters: " + rows.size() + " → " + csvOut.toAbsolutePath());
    }

    /** Also counts anonymous classes ({@code new Foo() { ... }}) per interface into {@code anonymousClasses}. */
    public static List<ParamRow> analyze(Path scanDir, Set<String> zeroImpl,
            Map<String, Integer> anonymousClasses) throws Exception {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));
        List<Path> javaFiles;
        try (var walk = Files.walk(scanDir)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }

        // first pass: index interfaces (trees aren't kept, to save heap)
        Map<String, List<Iface>> bySimpleName = new HashMap<>();
        for (Path file : javaFiles) {
            CompilationUnit cu = parse(parser, file, true);
            if (cu == null)
                continue;
            String pkg = packageOf(cu);
            for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (!decl.isInterface())
                    continue;
                String fqn = decl.getFullyQualifiedName().orElseGet(decl::getNameAsString);
                bySimpleName.computeIfAbsent(decl.getNameAsString(), k -> new ArrayList<>())
                        .add(new Iface(fqn, decl.getNameAsString(), pkg, pathOf(cu), decl.isTopLevelType(),
                                countAbstractMethods(decl), !decl.getExtendedTypes().isEmpty(),
                                decl.getAnnotationByName("FunctionalInterface").isPresent()));
            }
        }

        List<ParamRow> rows = new ArrayList<>();
        for (Path file : javaFiles) {
            CompilationUnit cu = parse(parser, file, false);
            if (cu == null)
                continue;
            for (MethodDeclaration m : cu.findAll(MethodDeclaration.class))
                collect(cu, m, m.getNameAsString(), "method", m.getParameters(), bySimpleName, zeroImpl, rows);
            for (ConstructorDeclaration c : cu.findAll(ConstructorDeclaration.class))
                collect(cu, c, c.getNameAsString(), "constructor", c.getParameters(), bySimpleName, zeroImpl, rows);
            for (RecordDeclaration r : cu.findAll(RecordDeclaration.class))
                collect(cu, r, r.getNameAsString(), "recordComponent", r.getParameters(), bySimpleName, zeroImpl,
                        rows);
            for (ObjectCreationExpr e : cu.findAll(ObjectCreationExpr.class)) {
                if (e.getAnonymousClassBody().isEmpty())
                    continue;
                Iface iface = resolve(e.getType().getNameWithScope(), cu, bySimpleName);
                if (iface != null && zeroImpl.contains(iface.fqn()))
                    anonymousClasses.merge(iface.fqn(), 1, Integer::sum);
            }
        }
        return rows;
    }

    private static void collect(CompilationUnit cu, Node member, String memberName, String memberKind,
            List<Parameter> params, Map<String, List<Iface>> bySimpleName, Set<String> zeroImpl,
            List<ParamRow> rows) {
        Set<String> typeParams = typeParametersInScope(member);
        TypeDeclaration<?> owner = member instanceof TypeDeclaration<?> td ? td
                : member.findAncestor(TypeDeclaration.class).orElse(null);
        String ownerName = owner == null ? "?" : owner.getFullyQualifiedName().orElseGet(owner::getNameAsString);

        for (Parameter p : params) {
            Type outer = p.getType().getElementType(); // unwrap arrays; varargs are already unwrapped
            // each interface counts once per parameter, e.g. Map<Foo, Foo>
            Set<String> seen = new HashSet<>();
            for (ClassOrInterfaceType t : p.getType().findAll(ClassOrInterfaceType.class)) {
                // skip "Outer" in "Outer.Inner"
                if (t.getParentNode().filter(n -> n instanceof ClassOrInterfaceType parent
                        && parent.getScope().filter(s -> s == t).isPresent()).isPresent())
                    continue;
                if (typeParams.contains(t.getNameAsString()) && t.getScope().isEmpty())
                    continue;
                Iface iface = resolve(t.getNameWithScope(), cu, bySimpleName);
                if (iface == null || !zeroImpl.contains(iface.fqn()) || !seen.add(iface.fqn()))
                    continue;
                rows.add(new ParamRow(iface, ownerName, declaringKind(owner, iface.fqn()), memberName, memberKind,
                        member.getBegin().map(b -> b.line).orElse(-1),
                        p.getNameAsString(), p.getType().asString(), t == outer, pathOf(cu),
                        p.getBegin().map(b -> b.line).orElse(-1)));
            }
        }
    }

    /** "self" when the interface takes itself as a parameter, else the kind of the declaring type. */
    private static String declaringKind(TypeDeclaration<?> owner, String ifaceFqn) {
        if (owner == null)
            return "unknown";
        if (owner.getFullyQualifiedName().map(ifaceFqn::equals).orElse(false))
            return "self";
        if (owner instanceof ClassOrInterfaceDeclaration c)
            return c.isInterface() ? "interface" : (c.isAbstract() ? "abstractClass" : "class");
        if (owner instanceof EnumDeclaration)
            return "enum";
        if (owner instanceof RecordDeclaration)
            return "record";
        return "other";
    }

    /** Abstract methods in the interface body, ignoring Object methods; -1 if any is generic. */
    static int countAbstractMethods(ClassOrInterfaceDeclaration decl) {
        int count = 0;
        for (MethodDeclaration m : decl.getMethods()) {
            if (m.getBody().isPresent() || m.isStatic() || m.isDefault() || m.isPrivate())
                continue;
            if (isObjectMethod(m))
                continue;
            if (!m.getTypeParameters().isEmpty())
                return -1;
            count++;
        }
        return count;
    }

    private static boolean isObjectMethod(MethodDeclaration m) {
        String name = m.getNameAsString();
        int params = m.getParameters().size();
        return (params == 0 && (name.equals("hashCode") || name.equals("toString")))
                || (params == 1 && name.equals("equals")
                        && m.getParameter(0).getType().asString().matches("(java\\.lang\\.)?Object"));
    }

    private static Set<String> typeParametersInScope(Node node) {
        Set<String> names = new HashSet<>();
        for (Node n = node; n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof NodeWithTypeParameters<?> withParams)
                withParams.getTypeParameters().forEach(tp -> names.add(tp.getNameAsString()));
        }
        return names;
    }

    /** Resolves by same file, explicit import, same package, then wildcard import; null if ambiguous. */
    static Iface resolve(String written, CompilationUnit cu, Map<String, List<Iface>> bySimpleName) {
        String simple = written.substring(written.lastIndexOf('.') + 1);
        List<Iface> candidates = bySimpleName.getOrDefault(simple, List.of());
        if (candidates.isEmpty())
            return null;

        if (written.contains(".")) {
            List<Iface> suffix = candidates.stream()
                    .filter(c -> c.fqn().equals(written) || c.fqn().endsWith("." + written)).toList();
            if (suffix.size() > 1)
                suffix = suffix.stream().filter(c -> c.pkg().equals(packageOf(cu))).toList();
            return suffix.size() == 1 ? suffix.get(0) : null;
        }

        String file = pathOf(cu);
        List<Iface> sameFile = candidates.stream().filter(c -> c.file().equals(file)).toList();
        if (!sameFile.isEmpty())
            return sameFile.size() == 1 ? sameFile.get(0) : null;

        List<String> explicit = new ArrayList<>();
        List<String> wildcards = new ArrayList<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk())
                wildcards.add(imp.getNameAsString());
            else if (imp.getName().getIdentifier().equals(simple))
                explicit.add(imp.getNameAsString());
        }
        if (!explicit.isEmpty()) {
            // an explicit import shadows every other same-named interface
            List<Iface> imported = candidates.stream().filter(c -> explicit.contains(c.fqn())).toList();
            return imported.size() == 1 ? imported.get(0) : null;
        }

        String pkg = packageOf(cu);
        List<Iface> samePackage = candidates.stream()
                .filter(c -> c.topLevel() && c.pkg().equals(pkg)).toList();
        if (!samePackage.isEmpty())
            return samePackage.size() == 1 ? samePackage.get(0) : null;

        List<Iface> viaWildcard = candidates.stream()
                .filter(c -> wildcards.stream().anyMatch(w -> c.fqn().equals(w + "." + simple))).toList();
        return viaWildcard.size() == 1 ? viaWildcard.get(0) : null;
    }

    /** Returns null if the file doesn't parse. */
    private static CompilationUnit parse(JavaParser parser, Path file, boolean report) {
        Optional<CompilationUnit> parsed;
        try {
            parsed = parser.parse(file).getResult();
        } catch (Exception e) {
            parsed = Optional.empty();
        }
        if (parsed.isEmpty() && report)
            System.err.println("Error parsing file: " + file);
        return parsed.orElse(null);
    }

    private static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    private static String pathOf(CompilationUnit cu) {
        return cu.getStorage().map(s -> s.getPath().toAbsolutePath().toString()).orElse("unknown");
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
