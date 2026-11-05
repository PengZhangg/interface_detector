package interfacedetector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * For every zero-implementation interface in an App output.json, finds where
 * it is referenced and classifies what (if anything) keeps it alive.
 * References are resolved by name only, so counts are lower bounds.
 *
 * Usage: ReferenceAnalyzer <scanDir> <output.json> <references.json>
 */
public class ReferenceAnalyzer {

    record Iface(String fqn, String simple, String pkg, String file, boolean topLevel,
            List<String> parents, int implCount, boolean generated) {
    }

    record MethodSig(String declaringType, String name, int arity, boolean constructor,
            boolean override, String file) {
    }

    /** A parameter position, keyed "name/arity" or "new Type/arity"; RETURN for the return value. */
    record Slot(String callee, int index) {
        static final int RETURN = -1;
    }

    static class Usage {
        final Map<String, Integer> counts = new TreeMap<>();
        final Set<String> externalFiles = new TreeSet<>();
        final Set<MethodSig> methods = new LinkedHashSet<>();
        final List<String> samples = new ArrayList<>();
        final List<String> subInterfaces = new ArrayList<>();
        int nonMethodTypeUses = 0;
        int abstractMethods = -1;
        final Set<String> annotations = new TreeSet<>();

        void add(String category, String file, boolean external, String where) {
            counts.merge(category, 1, Integer::sum);
            if (external)
                externalFiles.add(file);
            if (samples.size() < 5)
                samples.add(category + " @ " + where);
        }

        int count(String category) {
            return counts.getOrDefault(category, 0);
        }
    }

    /** Annotations whose implementing class is generated at build time (Helidon, SmallRye, Immutables). */
    private static final Set<String> CODEGEN_ANNOTATIONS = Set.of(
            "Prototype.Blueprint", "Blueprint", "ConfigMapping", "ConfigRoot", "ConfigGroup",
            "Value.Immutable", "Value.Modifiable");

    /** Annotations on constructors/methods that a DI container invokes reflectively. */
    private static final Set<String> DI_ANNOTATIONS = Set.of("Inject", "Provides", "Produces", "Bean");

    /** Argument kinds that implement the expected interface inline. */
    private static final Set<String> INLINE_IMPLEMENTATIONS = Set.of(
            "lambda", "methodReference", "anonymousClass");

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IDENT = Pattern.compile("[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*");

    private final Map<String, Iface> interfaces = new HashMap<>();
    private final Map<String, List<Iface>> simpleIndex = new HashMap<>();
    private final Map<String, Usage> usages = new TreeMap<>();
    private final Map<String, Map<Integer, Integer>> callCounts = new HashMap<>();
    private final Map<String, Map<Integer, Integer>> creationCounts = new HashMap<>();
    private final Set<String> methodRefNames = new HashSet<>();
    private int ambiguous = 0;

    // argument tracing
    private final Map<String, Integer> declarationCounts = new HashMap<>();
    private final Map<String, Set<Slot>> slotsByCallee = new HashMap<>();
    private final Map<String, Set<Slot>> interfaceSlots = new HashMap<>();
    private final Map<Slot, Map<String, Integer>> argumentKinds = new HashMap<>();
    private final Map<Slot, Set<Slot>> forwardedFrom = new HashMap<>();
    private final Map<Slot, List<String>> argumentSamples = new HashMap<>();

    private final Map<Slot, Set<String>> slotOwners = new HashMap<>();
    // methods declared to return a zero-impl interface, keyed like call sites
    private final Set<String> returnSlots = new HashSet<>();
    private final Map<Slot, Set<String>> slotInterfaceNames = new HashMap<>();
    private final Map<String, Set<String>> calleesByName = new HashMap<>();
    private final Set<Slot> pendingSlots = new HashSet<>();
    private Set<Slot> activeSlots = null; // null: every slot is traced
    private final Set<String> injectedCallables = new HashSet<>();
    private final Set<String> classLiteralNames = new HashSet<>();

    public static void main(String[] args) throws Exception {
        Path scanDir = Path.of(args[0]);
        Path inputJson = Path.of(args[1]);
        Path outputFile = args.length > 2 ? Path.of(args[2]) : Path.of("references.json");

        ReferenceAnalyzer analyzer = new ReferenceAnalyzer();
        analyzer.loadInterfaces(inputJson);
        analyzer.scan(scanDir);
        Map<String, Object> report = analyzer.report(scanDir);

        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), report);
        System.out.println("Output written to: " + outputFile.toAbsolutePath());
    }

    void loadInterfaces(Path inputJson) throws Exception {
        JsonNode root = new ObjectMapper().readTree(inputJson.toFile());
        Map<String, String> filePackages = new HashMap<>();
        for (JsonNode node : root.get("interfaces")) {
            String fqn = node.get("name").asString();
            String file = node.get("filePath").asString();
            String pkg = filePackages.computeIfAbsent(file, ReferenceAnalyzer::readPackage);
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            boolean topLevel = fqn.equals(pkg.isEmpty() ? simple : pkg + "." + simple);
            List<String> parents = new ArrayList<>();
            JsonNode ext = node.get("extendedInterfaces");
            if (ext != null)
                ext.forEach(p -> parents.add(p.asString()));
            int implCount = node.get("concreteImplementations").size() + node.get("abstractImplementations").size();
            boolean generated = file.contains("/generated/") || file.contains("/generated-sources/");
            Iface iface = new Iface(fqn, simple, pkg, file, topLevel, parents, implCount, generated);
            interfaces.put(fqn, iface);
            simpleIndex.computeIfAbsent(simple, k -> new ArrayList<>()).add(iface);
            if (implCount == 0)
                usages.put(fqn, new Usage());
        }
        // record sub-interfaces of each zero-impl interface
        for (Iface child : interfaces.values()) {
            for (String parent : child.parents()) {
                Usage u = usages.get(parent);
                if (u != null)
                    u.subInterfaces.add(child.fqn());
            }
        }
    }

    private static String readPackage(String file) {
        try {
            Matcher m = PACKAGE.matcher(Files.readString(Path.of(file)));
            return m.find() ? m.group(1) : "";
        } catch (Exception e) {
            return "";
        }
    }

    void scan(Path folder) throws Exception {
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21));
        List<Path> javaFiles;
        try (var walk = Files.walk(folder)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        // pass 1 collects references and slots; pass 2 traces arguments into
        // them; later passes trace only slots discovered while tracing
        for (int pass = 1; pass <= 5; pass++) {
            if (pass > 1) {
                if (pass > 2 && pendingSlots.isEmpty())
                    break;
                activeSlots = pass == 2 ? null : new HashSet<>(pendingSlots);
                pendingSlots.clear();
            }
            for (Path file : javaFiles) {
                var result = parser.parse(file);
                if (result.getResult().isEmpty()) {
                    if (pass == 1)
                        System.err.println("Error parsing file: " + file);
                    continue;
                }
                String path = file.toAbsolutePath().toString();
                if (pass == 1)
                    scanUnit(result.getResult().get(), path);
                else
                    traceArguments(result.getResult().get(), path, pass == 2);
            }
        }
    }

    private void registerSlot(Slot slot, String ownerSimpleName, String ifaceSimpleName) {
        slotsByCallee.computeIfAbsent(slot.callee(), k -> new HashSet<>()).add(slot);
        slotOwners.computeIfAbsent(slot, k -> new HashSet<>()).add(ownerSimpleName);
        slotInterfaceNames.computeIfAbsent(slot, k -> new HashSet<>()).add(ifaceSimpleName);
        String name = slot.callee().substring(0, slot.callee().lastIndexOf('/'));
        calleesByName.computeIfAbsent(name, k -> new HashSet<>()).add(slot.callee());
    }

    private boolean isActive(Slot slot) {
        return activeSlots == null || activeSlots.contains(slot);
    }

    private static String calleeKey(CallableDeclaration<?> c) {
        return (c instanceof ConstructorDeclaration ? "new " : "") + c.getNameAsString() + "/"
                + c.getParameters().size();
    }

    private void addKind(Slot slot, String kind, String where) {
        argumentKinds.computeIfAbsent(slot, k -> new TreeMap<>()).merge(kind, 1, Integer::sum);
        List<String> samples = argumentSamples.computeIfAbsent(slot, k -> new ArrayList<>());
        if (samples.size() < 5)
            samples.add(kind + " @ " + where);
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private void traceArguments(CompilationUnit cu, String file, boolean firstRound) {
        // "Type::method" gets its arguments from whoever invokes the functional interface
        for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class)) {
            String scope = ref.getScope().toString().replaceAll("<.*>", "");
            String scopeLast = scope.substring(scope.lastIndexOf('.') + 1);
            String name = ref.getIdentifier().equals("new") ? "new " + scopeLast : ref.getIdentifier();
            boolean typeScope = !scopeLast.isEmpty() && Character.isUpperCase(scopeLast.charAt(0));
            for (String callee : calleesByName.getOrDefault(name, Set.of())) {
                for (Slot slot : slotsByCallee.get(callee)) {
                    if (!isActive(slot) || (typeScope && !name.startsWith("new ")
                            && !slotOwners.getOrDefault(slot, Set.of()).contains(scopeLast)))
                        continue;
                    addKind(slot, "viaMethodReference", file + ":" + line(ref));
                }
            }
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class))
            traceCall(call.getNameAsString() + "/" + call.getArguments().size(), call.getArguments(), call, file);
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class))
            traceCall("new " + creation.getType().getNameAsString() + "/" + creation.getArguments().size(),
                    creation.getArguments(), creation, file);
        // enum constants "FOO(a, b)" call the enum's constructor
        for (EnumConstantDeclaration constant : cu.findAll(EnumConstantDeclaration.class))
            constant.findAncestor(EnumDeclaration.class).ifPresent(e -> traceCall(
                    "new " + e.getNameAsString() + "/" + constant.getArguments().size(),
                    constant.getArguments(), constant, file));
        for (ExplicitConstructorInvocationStmt stmt : cu.findAll(ExplicitConstructorInvocationStmt.class)) {
            var cls = stmt.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
            if (cls == null)
                continue;
            String target = stmt.isThis() ? cls.getNameAsString()
                    : cls.getExtendedTypes().isEmpty() ? null : cls.getExtendedTypes(0).getNameAsString();
            if (target != null)
                traceCall("new " + target + "/" + stmt.getArguments().size(), stmt.getArguments(), stmt, file);
        }
        if (firstRound)
            traceReturns(cu, file);
    }

    /** What do methods declared to return a zero-impl interface actually return? */
    private void traceReturns(CompilationUnit cu, String file) {
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            String key = calleeKey(m);
            if (!returnSlots.contains(key))
                continue;
            Slot slot = new Slot(key, Slot.RETURN);
            Map<String, Integer> kinds = argumentKinds.computeIfAbsent(slot, k -> new TreeMap<>());
            if (m.getBody().isEmpty()) {
                kinds.merge("abstractMethod", 1, Integer::sum);
                continue;
            }
            for (ReturnStmt ret : m.findAll(ReturnStmt.class)) {
                // skip returns of nested lambdas and anonymous classes
                Node owner = ret.getParentNode().orElse(null);
                while (owner != null && owner != m && !(owner instanceof LambdaExpr)
                        && !(owner instanceof CallableDeclaration))
                    owner = owner.getParentNode().orElse(null);
                if (owner != m || ret.getExpression().isEmpty())
                    continue;
                List<String> out = new ArrayList<>();
                classifyArgument(ret.getExpression().get(), slot, 0, out);
                for (String kind : out)
                    addKind(slot, kind, file + ":" + line(ret));
            }
        }
    }

    private void traceCall(String callee, List<Expression> args, Node site, String file) {
        Set<Slot> slots = slotsByCallee.get(callee);
        if (slots == null)
            return;
        // "Type.method(...)" can only target Type's own declarations
        String staticOwner = null;
        if (site instanceof MethodCallExpr call && call.getScope().isPresent()) {
            String scope = call.getScope().get().toString();
            String last = scope.substring(scope.lastIndexOf('.') + 1);
            if (IDENT.matcher(scope).matches() && !last.isEmpty() && Character.isUpperCase(last.charAt(0)))
                staticOwner = last;
        }
        String where = file + ":" + line(site);
        // copy: classifying can register new slots under this callee
        for (Slot slot : List.copyOf(slots)) {
            if (!isActive(slot))
                continue;
            if (staticOwner != null && !slotOwners.getOrDefault(slot, Set.of()).contains(staticOwner))
                continue;
            Expression arg = args.get(slot.index());
            // a literal can't be an instance of the interface: different same-named method
            if (arg instanceof ClassExpr || (arg instanceof LiteralExpr && !(arg instanceof NullLiteralExpr)))
                continue;
            List<String> kinds = new ArrayList<>();
            classifyArgument(arg, slot, 0, kinds);
            for (String kind : kinds)
                addKind(slot, kind, where);
        }
    }

    /**
     * Classifies an argument expression. Locals and fields are followed to the
     * values assigned to them; enclosing-method parameters are recorded as forwarded.
     */
    private void classifyArgument(Expression e, Slot target, int depth, List<String> out) {
        if (depth > 4) {
            out.add("other");
            return;
        }
        if (e instanceof EnclosedExpr x)
            classifyArgument(x.getInner(), target, depth + 1, out);
        else if (e instanceof CastExpr x)
            classifyArgument(x.getExpression(), target, depth + 1, out);
        else if (e instanceof ConditionalExpr x) {
            classifyArgument(x.getThenExpr(), target, depth + 1, out);
            classifyArgument(x.getElseExpr(), target, depth + 1, out);
        } else if (e instanceof LambdaExpr)
            out.add("lambda");
        else if (e instanceof MethodReferenceExpr)
            out.add("methodReference");
        else if (e instanceof ObjectCreationExpr x)
            out.add(x.getAnonymousClassBody().isPresent() ? "anonymousClass" : "namedClassInstance");
        else if (e instanceof NullLiteralExpr)
            out.add("null");
        else if (e instanceof ThisExpr)
            out.add(e.findAncestor(ClassOrInterfaceDeclaration.class).map(ClassOrInterfaceDeclaration::isInterface)
                    .orElse(false) ? "thisInInterface" : "this");
        else if (e instanceof MethodCallExpr call) {
            String key = call.getNameAsString() + "/" + call.getArguments().size();
            if (returnSlots.contains(key)) {
                forwardedFrom.computeIfAbsent(target, k -> new HashSet<>()).add(new Slot(key, Slot.RETURN));
                out.add("forwardedReturn");
            } else
                out.add("methodCallResult");
        } else if (e instanceof NameExpr x)
            classifyVariable(x.getNameAsString(), e, target, depth, out);
        else if (e instanceof FieldAccessExpr x && x.getScope() instanceof ThisExpr)
            classifyVariable(x.getNameAsString(), e, target, depth, out);
        else if (e instanceof FieldAccessExpr)
            out.add("field");
        else
            out.add("other");
    }

    private void classifyVariable(String name, Node use, Slot target, int depth, List<String> out) {
        Node n = use.getParentNode().orElse(null);
        while (n != null) {
            if (n instanceof LambdaExpr l && l.getParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) {
                out.add("lambdaParameter");
                return;
            }
            if (n instanceof CallableDeclaration<?> c) {
                var params = c.getParameters();
                for (int i = 0; i < params.size(); i++) {
                    if (params.get(i).getNameAsString().equals(name)) {
                        if (use instanceof FieldAccessExpr)
                            break; // "this.x" is the field, not the parameter
                        Slot source = new Slot(calleeKey(c), i);
                        forwardedFrom.computeIfAbsent(target, k -> new HashSet<>()).add(source);
                        // pass 1 may have missed this parameter's type; trace it if the name matches
                        String paramType = params.get(i).getType() instanceof ClassOrInterfaceType t
                                ? t.getNameAsString() : "";
                        if (!slotOwners.containsKey(source)
                                && slotInterfaceNames.getOrDefault(target, Set.of()).contains(paramType)) {
                            String owner = c.findAncestor(TypeDeclaration.class)
                                    .map(td -> ((TypeDeclaration<?>) td).getNameAsString()).orElse("?");
                            registerSlot(source, owner, paramType);
                            pendingSlots.add(source);
                        }
                        out.add("forwardedParameter");
                        return;
                    }
                }
                if (c.findAll(TypePatternExpr.class).stream().anyMatch(p -> p.getNameAsString().equals(name))) {
                    // "x instanceof Iface y"
                    out.add("instanceofPattern");
                    return;
                }
                Optional<VariableDeclarator> local = c.findAll(VariableDeclarator.class).stream()
                        .filter(v -> v.getNameAsString().equals(name)
                                && !(v.getParentNode().orElse(null) instanceof FieldDeclaration))
                        .findFirst();
                if (local.isPresent() && !(use instanceof FieldAccessExpr)) {
                    if (local.get().getInitializer().isPresent())
                        classifyArgument(local.get().getInitializer().get(), target, depth + 1, out);
                    else
                        out.add("localVariable");
                    return;
                }
            }
            if (n instanceof TypeDeclaration<?> t) {
                Optional<FieldDeclaration> field = t.getFieldByName(name);
                if (field.isPresent()) {
                    classifyField(t, name, field.get(), target, depth, out);
                    return;
                }
            }
            n = n.getParentNode().orElse(null);
        }
        out.add("otherVariable");
    }

    private void classifyField(TypeDeclaration<?> type, String name, FieldDeclaration field, Slot target,
            int depth, List<String> out) {
        List<Expression> values = new ArrayList<>();
        field.getVariables().stream().filter(v -> v.getNameAsString().equals(name))
                .forEach(v -> v.getInitializer().ifPresent(values::add));
        // also values assigned anywhere in the class, e.g. "this.x = x"
        for (AssignExpr assign : type.findAll(AssignExpr.class)) {
            Expression t = assign.getTarget();
            boolean targetsField = (t instanceof NameExpr ne && ne.getNameAsString().equals(name))
                    || (t instanceof FieldAccessExpr fa && fa.getScope() instanceof ThisExpr
                            && fa.getNameAsString().equals(name));
            if (targetsField)
                values.add(assign.getValue());
        }
        if (values.isEmpty()) {
            out.add("field");
            return;
        }
        for (Expression value : values)
            classifyArgument(value, target, depth + 1, out);
    }

    /** All argument kinds that can reach a slot, following forwarded parameters to their callers. */
    private Set<String> resolvedKinds(Slot slot, Set<Slot> visited) {
        Set<String> kinds = new TreeSet<>();
        Map<String, Integer> direct = argumentKinds.get(slot);
        if (direct == null) {
            if (!slotOwners.containsKey(slot))
                kinds.add("forwardedFromOtherType");
            else if (reflectivelyConstructed(slot.callee()))
                kinds.add("frameworkInjected");
            else
                kinds.add("noCallSites");
            return kinds;
        }
        for (String k : direct.keySet())
            if (!k.equals("forwardedParameter") && !k.equals("forwardedReturn"))
                kinds.add(k);
        for (Slot source : forwardedFrom.getOrDefault(slot, Set.of()))
            if (visited.add(source))
                kinds.addAll(resolvedKinds(source, visited));
        return kinds;
    }

    /** Callable that a DI container may invoke: DI-annotated, or a constructor of a class used as a literal. */
    private boolean reflectivelyConstructed(String callee) {
        if (injectedCallables.contains(callee))
            return true;
        if (!callee.startsWith("new "))
            return false;
        return classLiteralNames.contains(callee.substring(4, callee.lastIndexOf('/')));
    }

    /** How a zero-impl parameter type is satisfied, judged from what callers pass in. */
    private static String parameterVerdict(Set<String> kinds) {
        if (kinds.isEmpty())
            return null;
        if (kinds.stream().anyMatch(INLINE_IMPLEMENTATIONS::contains))
            return "receivesInlineImplementation";
        Set<String> rest = new TreeSet<>(kinds);
        rest.remove("noCallSites");
        if (rest.isEmpty())
            return "neverCalled";
        if (rest.equals(Set.of("frameworkInjected")))
            return "frameworkInjected";
        rest.remove("null");
        if (rest.isEmpty())
            return "onlyNullPassed";
        return "valueFromElsewhere";
    }

    /** Per-file name-resolution context. */
    private record Ctx(String file, String pkg, Set<String> imports, Set<String> wildcards) {
    }

    private void scanUnit(CompilationUnit cu, String file) {
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
        Set<String> imports = new HashSet<>();
        Set<String> wildcards = new HashSet<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isStatic())
                continue;
            (imp.isAsterisk() ? wildcards : imports).add(imp.getNameAsString());
        }
        Ctx ctx = new Ctx(file, pkg, imports, wildcards);

        for (ClassOrInterfaceDeclaration decl : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (!decl.isInterface())
                continue;
            Usage u = usages.get(decl.getFullyQualifiedName().orElse(""));
            if (u == null)
                continue;
            u.abstractMethods = (int) decl.getMethods().stream()
                    .filter(m -> m.getBody().isEmpty() && !m.isStatic() && !m.isDefault() && !m.isPrivate())
                    .count();
            decl.getAnnotations().forEach(a -> u.annotations.add(a.getNameAsString()));
        }

        for (ImportDeclaration imp : cu.getImports()) {
            String name = imp.getNameAsString();
            if (imp.isStatic()) {
                // "import static Iface.CONST" / "import static Iface.*"
                String owner = imp.isAsterisk() ? name : name.substring(0, Math.max(0, name.lastIndexOf('.')));
                if (usages.containsKey(owner))
                    record(usages.get(owner), interfaces.get(owner), "staticAccess", imp, ctx);
                continue;
            }
            if (imp.isAsterisk())
                continue;
            Usage u = usages.get(name);
            if (u != null)
                record(u, interfaces.get(name), "import", imp, ctx);
        }

        for (ClassOrInterfaceType type : cu.findAll(ClassOrInterfaceType.class)) {
            Iface target = resolve(type.getNameWithScope(), ctx);
            if (target == null || !usages.containsKey(target.fqn()) || insideDeclaration(type, target))
                continue;
            // "Outer" in "Outer.Inner": the interface is only a namespace
            if (type.getParentNode().orElse(null) instanceof ClassOrInterfaceType parent
                    && parent.getScope().map(s -> s == type).orElse(false))
                record(usages.get(target.fqn()), target, "asQualifier", type, ctx);
            else
                recordTypeUse(target, type, ctx);
        }

        // static member access: Iface.CONSTANT, Iface.method(), Iface::method
        for (Node scope : staticScopes(cu)) {
            String written = scope.toString();
            if (!IDENT.matcher(written).matches())
                continue;
            Iface target = resolve(written, ctx);
            if (target == null || !usages.containsKey(target.fqn()) || insideDeclaration(scope, target))
                continue;
            record(usages.get(target.fqn()), target, "staticAccess", scope, ctx);
        }

        for (Comment comment : cu.getAllContainedComments()) {
            if (!comment.isJavadocComment())
                continue;
            Matcher m = IDENT.matcher(comment.getContent());
            Set<String> seen = new HashSet<>();
            while (m.find()) {
                Iface target = resolve(m.group(), ctx);
                if (target != null && usages.containsKey(target.fqn()) && seen.add(target.fqn())
                        && !insideDeclaration(comment.getCommentedNode().orElse(comment), target))
                    record(usages.get(target.fqn()), target, "javadoc", comment, ctx);
            }
        }

        for (CallableDeclaration<?> c : cu.findAll(CallableDeclaration.class)) {
            declarationCounts.merge(calleeKey(c), 1, Integer::sum);
            if (DI_ANNOTATIONS.stream().anyMatch(a -> c.getAnnotationByName(a).isPresent()))
                injectedCallables.add(calleeKey(c));
        }
        for (ClassExpr literal : cu.findAll(ClassExpr.class))
            if (literal.getType() instanceof ClassOrInterfaceType t)
                classLiteralNames.add(t.getNameAsString());
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class))
            callCounts.computeIfAbsent(call.getNameAsString(), k -> new HashMap<>())
                    .merge(call.getArguments().size(), 1, Integer::sum);
        for (ObjectCreationExpr creation : cu.findAll(ObjectCreationExpr.class))
            creationCounts.computeIfAbsent(creation.getType().getNameAsString(), k -> new HashMap<>())
                    .merge(creation.getArguments().size(), 1, Integer::sum);
        for (MethodReferenceExpr ref : cu.findAll(MethodReferenceExpr.class))
            methodRefNames.add(ref.getIdentifier());
    }

    private static List<Node> staticScopes(CompilationUnit cu) {
        List<Node> scopes = new ArrayList<>();
        for (FieldAccessExpr f : cu.findAll(FieldAccessExpr.class))
            if (f.getScope() instanceof NameExpr || f.getScope() instanceof FieldAccessExpr)
                scopes.add(f.getScope());
        for (MethodCallExpr c : cu.findAll(MethodCallExpr.class))
            c.getScope().filter(s -> s instanceof NameExpr || s instanceof FieldAccessExpr).ifPresent(scopes::add);
        for (MethodReferenceExpr r : cu.findAll(MethodReferenceExpr.class))
            if (r.getScope() instanceof NameExpr || r.getScope() instanceof FieldAccessExpr)
                scopes.add(r.getScope());
        // "a.B.C" yields both "a.B" and "a.B.C"; keep only the outermost
        Set<Node> inner = new HashSet<>();
        for (Node s : scopes)
            if (s instanceof FieldAccessExpr f)
                inner.add(f.getScope());
        scopes.removeIf(inner::contains);
        return scopes;
    }

    private void recordTypeUse(Iface target, ClassOrInterfaceType type, Ctx ctx) {
        Usage u = usages.get(target.fqn());
        Node parent = type.getParentNode().orElse(null);

        if (parent instanceof ClassOrInterfaceDeclaration decl) {
            if (decl.getExtendedTypes().contains(type))
                record(u, target, decl.isInterface() ? "extendedBy" : "classExtends", type, ctx);
            else // implements clause App could not resolve
                record(u, target, "implementsUnresolved", type, ctx);
            return;
        }
        if (parent instanceof ObjectCreationExpr creation && creation.getType() == type) {
            record(u, target, creation.getAnonymousClassBody().isPresent() ? "anonymousClass" : "instantiation",
                    type, ctx);
            return;
        }
        if (parent instanceof ClassExpr) {
            record(u, target, "classLiteral", type, ctx);
            return;
        }

        record(u, target, "typeUse", type, ctx);
        CallableDeclaration<?> callable = signatureOwner(type);
        if (callable == null) {
            u.nonMethodTypeUses++;
            return;
        }
        String owner = callable.findAncestor(TypeDeclaration.class)
                .map(t -> ((TypeDeclaration<?>) t).getFullyQualifiedName().orElse("?")).orElse("?");
        if (callable instanceof MethodDeclaration m && m.getType() == type)
            returnSlots.add(calleeKey(m));
        if (parent instanceof Parameter p && p.getType() == type) {
            var params = callable.getParameters();
            for (int i = 0; i < params.size(); i++) {
                if (params.get(i) == p) {
                    Slot slot = new Slot(calleeKey(callable), i);
                    registerSlot(slot, owner.substring(owner.lastIndexOf('.') + 1), target.simple());
                    interfaceSlots.computeIfAbsent(target.fqn(), k -> new LinkedHashSet<>()).add(slot);
                }
            }
        }
        boolean override = callable.getAnnotationByName("Override").isPresent();
        u.methods.add(new MethodSig(owner, callable.getNameAsString(), callable.getParameters().size(),
                callable instanceof ConstructorDeclaration, override, ctx.file()));
    }

    /** The method/constructor whose signature contains this type, or null if used elsewhere. */
    private static CallableDeclaration<?> signatureOwner(Node type) {
        Node child = type;
        Node parent = type.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof Parameter) {
                child = parent;
                parent = parent.getParentNode().orElse(null);
                continue;
            }
            if (parent instanceof MethodDeclaration m)
                return (m.getType() == child || m.getParameters().contains(child)) ? m : null;
            if (parent instanceof ConstructorDeclaration c)
                return c.getParameters().contains(child) ? c : null;
            if (!(parent instanceof com.github.javaparser.ast.type.Type))
                return null;
            child = parent;
            parent = parent.getParentNode().orElse(null);
        }
        return null;
    }

    private boolean insideDeclaration(Node node, Iface target) {
        Node n = node;
        while (n != null) {
            if (n instanceof ClassOrInterfaceDeclaration d && d.isInterface()
                    && d.getFullyQualifiedName().map(target.fqn()::equals).orElse(false))
                return true;
            n = n.getParentNode().orElse(null);
        }
        return false;
    }

    private void record(Usage u, Iface target, String category, Node node, Ctx ctx) {
        boolean external = !ctx.file().equals(target.file());
        u.add(category, ctx.file(), external, ctx.file() + ":" + line(node));
    }

    /**
     * Maps a written type name to the interface it refers to, or null. Unlike
     * App, the candidate must also be visible from the referencing file.
     */
    private Iface resolve(String written, Ctx ctx) {
        String simple = written.substring(written.lastIndexOf('.') + 1);
        List<Iface> candidates = simpleIndex.get(simple);
        if (candidates == null)
            return null;
        boolean qualified = written.contains(".");
        List<Iface> visible = new ArrayList<>();
        for (Iface c : candidates) {
            if (qualified && !(c.fqn().equals(written) || c.fqn().endsWith("." + written)))
                continue;
            if (isVisible(c, written, qualified, ctx))
                visible.add(c);
        }
        if (visible.size() == 1)
            return visible.get(0);
        if (visible.size() > 1) {
            List<Iface> sameFile = visible.stream().filter(c -> c.file().equals(ctx.file())).toList();
            if (sameFile.size() == 1)
                return sameFile.get(0);
            ambiguous++;
        }
        return null;
    }

    private static boolean isVisible(Iface c, String written, boolean qualified, Ctx ctx) {
        if (c.file().equals(ctx.file()) || c.fqn().equals(written) || ctx.imports().contains(c.fqn()))
            return true;
        if (qualified) {
            // "Outer.Inner": the outermost segment must be visible
            String outerFqn = c.fqn().substring(0, c.fqn().length() - written.length())
                    + written.substring(0, written.indexOf('.'));
            String outerPkg = outerFqn.contains(".") ? outerFqn.substring(0, outerFqn.lastIndexOf('.')) : "";
            return ctx.imports().contains(outerFqn) || ctx.wildcards().contains(outerPkg)
                    || outerPkg.equals(ctx.pkg());
        }
        String enclosing = c.fqn().substring(0, c.fqn().lastIndexOf('.') < 0 ? 0 : c.fqn().lastIndexOf('.'));
        if (ctx.wildcards().contains(enclosing))
            return true;
        return c.topLevel() && c.pkg().equals(ctx.pkg());
    }

    private boolean hasCaller(MethodSig m) {
        if (m.override())
            return true; // reached through the overridden supertype method
        Map<String, Map<Integer, Integer>> index = m.constructor() ? creationCounts : callCounts;
        String key = m.constructor() ? m.declaringType().substring(m.declaringType().lastIndexOf('.') + 1)
                : m.name();
        if (index.getOrDefault(key, Map.of()).getOrDefault(m.arity(), 0) > 0)
            return true;
        return !m.constructor() && methodRefNames.contains(m.name());
    }

    /** Some implemented interface below this one in the extends hierarchy, or null. */
    private String implementedDescendant(String fqn, Set<String> visited) {
        Usage u = usages.get(fqn);
        List<String> children = u != null ? u.subInterfaces : childrenOf(fqn);
        for (String child : children) {
            if (!visited.add(child))
                continue;
            Iface c = interfaces.get(child);
            if (c != null && c.implCount() > 0)
                return child;
            String deeper = implementedDescendant(child, visited);
            if (deeper != null)
                return deeper;
        }
        return null;
    }

    private List<String> childrenOf(String fqn) {
        List<String> out = new ArrayList<>();
        for (Iface i : interfaces.values())
            if (i.parents().contains(fqn))
                out.add(i.fqn());
        return out;
    }

    /** First category that explains why a zero-impl interface might still be needed; order matters. */
    private String classify(Iface iface, Usage u, List<MethodSig> called, List<MethodSig> uncalled) {
        if (implementedDescendant(iface.fqn(), new HashSet<>()) != null)
            return "implementedViaSubInterface";
        if (u.count("anonymousClass") > 0 || u.count("implementsUnresolved") > 0)
            return "anonymousOrUnresolvedImpl";
        if (u.annotations.stream().anyMatch(CODEGEN_ANNOTATIONS::contains))
            return "implementedByCodegen";
        boolean liveUse = u.count("classLiteral") > 0 || u.count("staticAccess") > 0
                || u.nonMethodTypeUses > 0 || !called.isEmpty();
        if (liveUse)
            return (u.abstractMethods == 1 && u.count("typeUse") > 0) ? "referencedLikelyLambda" : "referenced";
        if (u.count("asQualifier") > 0)
            return "namespaceOnly";
        if (!uncalled.isEmpty())
            return "onlyInUncalledMethods";
        if (u.count("extendedBy") > 0)
            return "onlyExtendedByUnusedInterfaces";
        if (u.count("import") > 0 || u.count("javadoc") > 0)
            return "onlyImportOrJavadoc";
        return "unreferenced";
    }

    Map<String, Object> report(Path scanDir) {
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Integer> summary = new TreeMap<>();
        Map<String, Integer> summaryHandwritten = new TreeMap<>();
        Map<String, Integer> parameterSummary = new TreeMap<>();
        Map<String, Integer> parameterSummaryHandwritten = new TreeMap<>();
        for (var e : usages.entrySet()) {
            Iface iface = interfaces.get(e.getKey());
            Usage u = e.getValue();
            List<MethodSig> called = new ArrayList<>();
            List<MethodSig> uncalled = new ArrayList<>();
            for (MethodSig m : u.methods)
                (hasCaller(m) ? called : uncalled).add(m);
            String category = classify(iface, u, called, uncalled);
            summary.merge(category, 1, Integer::sum);
            if (!iface.generated())
                summaryHandwritten.merge(category, 1, Integer::sum);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", iface.fqn());
            entry.put("filePath", iface.file());
            entry.put("generated", iface.generated());
            entry.put("category", category);
            entry.put("abstractMethods", u.abstractMethods);
            entry.put("annotations", u.annotations);
            entry.put("implementedVia", implementedDescendant(iface.fqn(), new HashSet<>()));
            entry.put("referenceCounts", u.counts);
            entry.put("externalFiles", u.externalFiles.size());
            entry.put("subInterfaces", u.subInterfaces);
            entry.put("methodsCalled", called.stream().map(ReferenceAnalyzer::describe).toList());
            entry.put("methodsUncalled", uncalled.stream().map(ReferenceAnalyzer::describe).toList());
            entry.put("samples", u.samples);

            Set<String> resolved = new TreeSet<>();
            List<Map<String, Object>> slots = new ArrayList<>();
            for (Slot slot : interfaceSlots.getOrDefault(iface.fqn(), Set.of())) {
                Set<String> kinds = resolvedKinds(slot, new HashSet<>(Set.of(slot)));
                resolved.addAll(kinds);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("callee", slot.callee());
                s.put("parameterIndex", slot.index());
                // other declarations share this name/arity
                s.put("ambiguousCallee", declarationCounts.getOrDefault(slot.callee(), 0) > 1);
                s.put("argumentKinds", argumentKinds.getOrDefault(slot, Map.of()));
                s.put("resolvedKinds", kinds);
                s.put("samples", argumentSamples.getOrDefault(slot, List.of()));
                slots.add(s);
            }
            String verdict = parameterVerdict(resolved);
            entry.put("parameterVerdict", verdict);
            entry.put("parameterSlots", slots);
            if (verdict != null) {
                parameterSummary.merge(verdict, 1, Integer::sum);
                if (!iface.generated())
                    parameterSummaryHandwritten.merge(verdict, 1, Integer::sum);
            }
            entries.add(entry);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scannedDirectory", scanDir.toAbsolutePath().toString());
        report.put("zeroImplInterfaces", usages.size());
        report.put("ambiguousReferencesSkipped", ambiguous);
        report.put("summary", summary);
        report.put("summaryHandwritten", summaryHandwritten);
        report.put("parameterSummary", parameterSummary);
        report.put("parameterSummaryHandwritten", parameterSummaryHandwritten);
        report.put("interfaces", entries);
        return report;
    }

    private static String describe(MethodSig m) {
        return m.declaringType() + "#" + m.name() + "/" + m.arity() + (m.override() ? " @Override" : "");
    }
}
