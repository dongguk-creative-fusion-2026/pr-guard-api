package com.prguard.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Java 소스 트리를 파싱해 {@link RepoIndex} 를 만든다.
 *
 * <p>심볼 해석기(타입 추론) 없이 import·같은 패키지·필드/변수 선언 타입만으로 호출 대상을 정한다.
 * Spring 처럼 필드 주입한 객체의 메서드를 부르는 코드는 이걸로 대부분 해석되고,
 * 메서드 체이닝처럼 타입을 모르는 호출은 {@link CallSite.Resolution#UNKNOWN_SCOPE} 로 남긴다.
 */
@Component
public class JavaIndexer {

    private static final int MAX_FILES = 5000;
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final Set<String> SKIP_DIRS = Set.of("build", "target", "out", "node_modules", ".git", ".gradle",
            "generated", ".idea");
    private static final Set<String> GENERATING_ANNOTATIONS = Set.of("Getter", "Setter", "Data", "Value", "Builder",
            "RequiredArgsConstructor", "AllArgsConstructor", "NoArgsConstructor", "Delegate", "SuperBuilder");
    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "getClass", "notify",
            "notifyAll", "wait", "clone", "finalize");

    public RepoIndex index(Path root) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false));

        // 1. 파싱
        Map<String, CompilationUnit> units = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (Path file : javaFiles(root)) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            try {
                ParseResult<CompilationUnit> result = parser.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (result.getResult().isPresent() && result.isSuccessful()) {
                    units.put(rel, result.getResult().get());
                } else {
                    failed.add(rel);
                }
            } catch (IOException | RuntimeException e) {
                failed.add(rel);
            }
        }

        // 2. 레포 안 타입 이름 수집 (해석 기준)
        Set<String> typeNames = new LinkedHashSet<>();
        units.values().forEach(cu -> cu.findAll(TypeDeclaration.class)
                .forEach(td -> fqn(td).ifPresent(typeNames::add)));

        // 3. 타입·메서드
        Map<String, TypeInfo> types = new LinkedHashMap<>();
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        Map<String, Resolver> resolvers = new HashMap<>();
        for (var entry : units.entrySet()) {
            String file = entry.getKey();
            Resolver resolver = new Resolver(entry.getValue(), typeNames);
            resolvers.put(file, resolver);
            boolean test = isTest(file);
            for (TypeDeclaration<?> td : entry.getValue().findAll(TypeDeclaration.class)) {
                Optional<String> fqn = fqn(td);
                if (fqn.isEmpty()) {
                    continue;
                }
                TypeInfo type = typeInfo(td, fqn.get(), file, resolver, test);
                types.put(type.fqn(), type);
                for (MethodInfo m : methodsOf(td, type)) {
                    methods.putIfAbsent(m.id(), m);
                }
            }
        }

        // 4. 호출 관계
        RepoIndex structure = new RepoIndex(types, methods, List.of(), units.size(), failed);
        List<CallSite> calls = new ArrayList<>();
        for (var entry : units.entrySet()) {
            String file = entry.getKey();
            Resolver resolver = resolvers.get(file);
            for (TypeDeclaration<?> td : entry.getValue().findAll(TypeDeclaration.class)) {
                Optional<String> typeFqn = fqn(td);
                if (typeFqn.isEmpty()) {
                    continue;
                }
                for (CallableDeclaration<?> callable : callables(td)) {
                    String callerId = methodId(typeFqn.get(), callable);
                    new CallCollector(structure, resolver, typeFqn.get(), callerId, file, callable,
                            callable.getParameters()).collect(calls);
                }
                // 필드 초기화식과 초기화 블록 (예: private final Foo foo = new Foo(a, b);)
                for (var member : td.getMembers()) {
                    if (member instanceof FieldDeclaration || member instanceof InitializerDeclaration) {
                        new CallCollector(structure, resolver, typeFqn.get(), typeFqn.get() + "#<field-init>", file,
                                member, List.of()).collect(calls);
                    }
                }
            }
        }
        return new RepoIndex(types, methods, calls, units.size(), failed);
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .filter(p -> {
                        try {
                            return Files.size(p) <= MAX_FILE_BYTES;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .sorted()
                    .limit(MAX_FILES)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean isTest(String file) {
        return file.contains("src/test/") || file.startsWith("test/");
    }

    private static Optional<String> fqn(TypeDeclaration<?> td) {
        try {
            return td.getFullyQualifiedName();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static TypeInfo typeInfo(TypeDeclaration<?> td, String fqn, String file, Resolver resolver, boolean test) {
        String kind;
        List<ClassOrInterfaceType> supers = new ArrayList<>();
        if (td instanceof ClassOrInterfaceDeclaration c) {
            kind = c.isInterface() ? "interface" : "class";
            supers.addAll(c.getExtendedTypes());
            supers.addAll(c.getImplementedTypes());
        } else if (td instanceof RecordDeclaration r) {
            kind = "record";
            supers.addAll(r.getImplementedTypes());
        } else if (td instanceof EnumDeclaration e) {
            kind = "enum";
            supers.addAll(e.getImplementedTypes());
        } else {
            kind = "annotation";
        }
        List<String> superTypes = supers.stream()
                .map(t -> resolver.resolve(t.getNameWithScope(), fqn))
                .toList();

        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldDeclaration field : td.getFields()) {
            for (VariableDeclarator v : field.getVariables()) {
                fields.put(v.getNameAsString(), resolver.resolve(typeName(v.getType()), fqn));
            }
        }
        if (td instanceof RecordDeclaration r) {
            r.getParameters().forEach(p -> fields.put(p.getNameAsString(), resolver.resolve(typeName(p.getType()), fqn)));
        }
        boolean generated = td.getAnnotations().stream()
                .anyMatch(a -> GENERATING_ANNOTATIONS.contains(a.getName().getIdentifier()));
        return new TypeInfo(fqn, td.getNameAsString(), kind, file, line(td), annotations(td), superTypes,
                fields, generated, test);
    }

    private static List<CallableDeclaration<?>> callables(TypeDeclaration<?> td) {
        List<CallableDeclaration<?>> result = new ArrayList<>();
        // 직속 멤버만. 중첩 타입의 메서드는 그 타입에서 따로 처리한다
        for (var member : td.getMembers()) {
            if (member instanceof MethodDeclaration m) {
                result.add(m);
            } else if (member instanceof ConstructorDeclaration c) {
                result.add(c);
            }
        }
        return result;
    }

    private static List<MethodInfo> methodsOf(TypeDeclaration<?> td, TypeInfo type) {
        List<MethodInfo> result = new ArrayList<>();
        boolean hasConstructor = false;
        for (CallableDeclaration<?> callable : callables(td)) {
            boolean constructor = callable instanceof ConstructorDeclaration;
            hasConstructor |= constructor;
            List<String> params = callable.getParameters().stream().map(JavaIndexer::paramType).toList();
            String returnType = callable instanceof MethodDeclaration m ? typeName(m.getType()) : "void";
            Optional<? extends Node> body = callable instanceof MethodDeclaration m
                    ? m.getBody() : Optional.of(((ConstructorDeclaration) callable).getBody());
            List<String> annotations = annotations(callable);
            Set<String> modifiers = callable.getModifiers().stream()
                    .map(mod -> mod.getKeyword().asString())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            result.add(new MethodInfo(
                    methodId(type.fqn(), callable), type.fqn(),
                    constructor ? "<init>" : callable.getNameAsString(), params, returnType,
                    annotations, modifiers, type.file(), line(callable), endLine(callable),
                    body.map(JavaIndexer::bodyHash).orElse(""),
                    hash(String.join("\n", annotations) + "|" + String.join(" ", modifiers)),
                    constructor, false, type.test()));
        }
        // 소스에 없지만 존재하는 메서드
        if (td instanceof RecordDeclaration r) {
            List<String> componentTypes = new ArrayList<>();
            for (Parameter p : r.getParameters()) {
                componentTypes.add(paramType(p));
                String id = type.fqn() + "#" + p.getNameAsString() + "()";
                if (result.stream().noneMatch(m -> m.id().equals(id))) {
                    result.add(implicit(type, p.getNameAsString(), List.of(), id));
                }
            }
            String canonical = type.fqn() + "#<init>(" + String.join(",", componentTypes) + ")";
            if (result.stream().noneMatch(m -> m.id().equals(canonical))) {
                result.add(implicit(type, "<init>", componentTypes, canonical));
            }
        } else if (!hasConstructor && type.kind().equals("class")) {
            result.add(implicit(type, "<init>", List.of(), type.fqn() + "#<init>()"));
        }
        return result;
    }

    private static MethodInfo implicit(TypeInfo type, String name, List<String> params, String id) {
        return new MethodInfo(id, type.fqn(), name, params, "", List.of(), Set.of("public"), type.file(),
                type.line(), type.line(), "", "", name.equals("<init>"), true, type.test());
    }

    static String methodId(String typeFqn, CallableDeclaration<?> callable) {
        String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
        String params = callable.getParameters().stream().map(JavaIndexer::paramType).collect(Collectors.joining(","));
        return typeFqn + "#" + name + "(" + params + ")";
    }

    private static String paramType(Parameter p) {
        return typeName(p.getType()) + (p.isVarArgs() ? "..." : "");
    }

    /** 제네릭을 뺀 타입 이름. {@code List<Post>} → {@code List}, {@code Post[]} → {@code Post[]}. */
    static String typeName(Type type) {
        String s = type.asString();
        int generic = s.indexOf('<');
        if (generic >= 0) {
            int close = s.lastIndexOf('>');
            s = s.substring(0, generic) + (close >= 0 ? s.substring(close + 1) : "");
        }
        return s.replaceAll("@\\S+\\s+", "").trim();
    }

    private static List<String> annotations(NodeWithAnnotations<?> node) {
        List<String> result = new ArrayList<>();
        for (AnnotationExpr a : node.getAnnotations()) {
            result.add(a.toString().replaceAll("\\s+", " "));
        }
        return result;
    }

    private static String bodyHash(Node body) {
        Node copy = body.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        return hash(copy.toString());
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int line(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private static int endLine(Node node) {
        return node.getEnd().map(p -> p.line).orElse(0);
    }

    /** 소스에 적힌 타입 이름을 레포 안 FQN 으로 바꾼다. 레포 밖이면 그대로 둔다. */
    static final class Resolver {

        private final String pkg;
        private final Map<String, String> explicitImports = new HashMap<>();
        private final List<String> wildcardImports = new ArrayList<>();
        /** import static a.b.C.name → name → a.b.C */
        private final Map<String, String> staticMembers = new HashMap<>();
        /** import static a.b.C.* → a.b.C */
        private final List<String> staticWildcards = new ArrayList<>();
        private final Set<String> typeNames;

        Resolver(CompilationUnit cu, Set<String> typeNames) {
            this.typeNames = typeNames;
            this.pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            NodeList<ImportDeclaration> imports = cu.getImports();
            for (ImportDeclaration imp : imports) {
                if (imp.isStatic()) {
                    if (imp.isAsterisk()) {
                        staticWildcards.add(imp.getNameAsString());
                    } else {
                        imp.getName().getQualifier().ifPresent(owner ->
                                staticMembers.put(imp.getName().getIdentifier(), owner.asString()));
                    }
                    continue;
                }
                if (imp.isAsterisk()) {
                    wildcardImports.add(imp.getNameAsString());
                } else {
                    explicitImports.put(imp.getName().getIdentifier(), imp.getNameAsString());
                }
            }
        }

        String staticImportOwner(String member) {
            return staticMembers.get(member);
        }

        boolean hasStaticWildcards() {
            return !staticWildcards.isEmpty();
        }

        boolean isProjectType(String name) {
            return typeNames.contains(name);
        }

        String resolve(String raw, String contextFqn) {
            String name = raw.replace("[]", "").replace("...", "").trim();
            int generic = name.indexOf('<');
            if (generic >= 0) {
                name = name.substring(0, generic);
            }
            if (typeNames.contains(name)) {
                return name;
            }
            if (name.contains(".")) {
                String head = name.substring(0, name.indexOf('.'));
                String resolvedHead = resolve(head, contextFqn);
                String candidate = resolvedHead + name.substring(head.length());
                return typeNames.contains(candidate) ? candidate : name;
            }
            // 바깥 타입 안에 중첩된 타입
            String outer = contextFqn;
            while (outer != null && !outer.isEmpty()) {
                String candidate = outer + "." + name;
                if (typeNames.contains(candidate)) {
                    return candidate;
                }
                int dot = outer.lastIndexOf('.');
                outer = dot < 0 ? null : outer.substring(0, dot);
            }
            String imported = explicitImports.get(name);
            if (imported != null) {
                return imported;
            }
            String samePackage = pkg.isEmpty() ? name : pkg + "." + name;
            if (typeNames.contains(samePackage)) {
                return samePackage;
            }
            for (String wildcard : wildcardImports) {
                String candidate = wildcard + "." + name;
                if (typeNames.contains(candidate)) {
                    return candidate;
                }
            }
            return name;
        }
    }

    /** 메서드 하나 안의 호출을 모은다. */
    private static final class CallCollector {

        private final RepoIndex structure;
        private final Resolver resolver;
        private final String typeFqn;
        private final String callerId;
        private final String file;
        private final Node callable;
        private final Map<String, String> localTypes = new HashMap<>();

        CallCollector(RepoIndex structure, Resolver resolver, String typeFqn, String callerId, String file,
                      Node callable, List<Parameter> parameters) {
            this.structure = structure;
            this.resolver = resolver;
            this.typeFqn = typeFqn;
            this.callerId = callerId;
            this.file = file;
            this.callable = callable;
            for (Parameter p : parameters) {
                localTypes.put(p.getNameAsString(), resolver.resolve(typeName(p.getType()), typeFqn));
            }
            for (VariableDeclarator v : callable.findAll(VariableDeclarator.class)) {
                String declared = typeName(v.getType());
                if (declared.equals("var") && v.getInitializer().isPresent()
                        && v.getInitializer().get() instanceof ObjectCreationExpr oc) {
                    declared = typeName(oc.getType());
                }
                if (!declared.equals("var")) {
                    localTypes.put(v.getNameAsString(), resolver.resolve(declared, typeFqn));
                }
            }
            for (CatchClause c : callable.findAll(CatchClause.class)) {
                localTypes.put(c.getParameter().getNameAsString(),
                        resolver.resolve(typeName(c.getParameter().getType()), typeFqn));
            }
        }

        void collect(List<CallSite> out) {
            for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
                Scope scope = call.getScope().map(this::scopeOf).orElseGet(() -> unscoped(call.getNameAsString()));
                out.add(site(call.getNameAsString(), call.getArguments().size(), scope, line(call)));
            }
            for (ObjectCreationExpr creation : callable.findAll(ObjectCreationExpr.class)) {
                if (creation.getAnonymousClassBody().isPresent()) {
                    continue;
                }
                String type = resolver.resolve(creation.getType().getNameWithScope(), typeFqn);
                Scope scope = resolver.isProjectType(type) ? Scope.project(type) : Scope.external(type);
                out.add(site("<init>", creation.getArguments().size(), scope, line(creation)));
            }
            for (MethodReferenceExpr ref : callable.findAll(MethodReferenceExpr.class)) {
                Scope scope = ref.getScope() instanceof TypeExpr t
                        ? typeScope(resolver.resolve(t.getType().asString(), typeFqn))
                        : scopeOf(ref.getScope());
                String name = ref.getIdentifier().equals("new") ? "<init>" : ref.getIdentifier();
                out.add(site(name, -1, scope, line(ref)));
            }
        }

        private CallSite site(String name, int argCount, Scope scope, int line) {
            if (scope.type() == null) {
                return new CallSite(callerId, name, argCount, null, List.of(), CallSite.Resolution.UNKNOWN_SCOPE,
                        file, line);
            }
            if (!scope.project()) {
                return new CallSite(callerId, name, argCount, null, List.of(), CallSite.Resolution.EXTERNAL,
                        file, line);
            }
            List<MethodInfo> byName = new ArrayList<>();
            for (TypeInfo t : structure.hierarchy(scope.type())) {
                structure.methods().values().stream()
                        .filter(m -> m.typeFqn().equals(t.fqn()) && m.name().equals(name))
                        .forEach(byName::add);
            }
            List<String> matching = byName.stream()
                    .filter(m -> argCount < 0 || m.acceptsArgs(argCount))
                    .map(MethodInfo::id)
                    .toList();
            if (matching.isEmpty() && !byName.isEmpty()) {
                // 인자 수가 안 맞아도 같은 이름이면 그쪽을 부르려던 것으로 본다 (컴파일 오류 후보)
                matching = byName.stream().map(MethodInfo::id).toList();
            }
            if (!matching.isEmpty()) {
                return new CallSite(callerId, name, argCount, scope.type(), matching, CallSite.Resolution.RESOLVED,
                        file, line);
            }
            boolean mayExistOutside = structure.hasExternalHierarchy(scope.type()) || OBJECT_METHODS.contains(name);
            return new CallSite(callerId, name, argCount, scope.type(), List.of(),
                    mayExistOutside ? CallSite.Resolution.EXTERNAL : CallSite.Resolution.MISSING, file, line);
        }

        /**
         * 대상 없이 부른 메서드: 자신과 상위 타입 → 바깥 타입(중첩 클래스) → static import 순으로 찾는다.
         */
        private Scope unscoped(String name) {
            String outer = typeFqn;
            while (outer != null) {
                if (structure.types().containsKey(outer)) {
                    for (TypeInfo t : structure.hierarchy(outer)) {
                        String owner = t.fqn();
                        if (structure.methods().values().stream()
                                .anyMatch(m -> m.typeFqn().equals(owner) && m.name().equals(name))) {
                            return Scope.project(outer);
                        }
                    }
                }
                int dot = outer.lastIndexOf('.');
                outer = dot < 0 ? null : outer.substring(0, dot);
            }
            String imported = resolver.staticImportOwner(name);
            if (imported != null) {
                return typeScope(imported);
            }
            if (resolver.hasStaticWildcards()) {
                // import static org.mockito.Mockito.* 처럼 어디서 왔는지 모르는 이름
                return Scope.unknown();
            }
            return Scope.project(typeFqn);
        }

        private Scope scopeOf(Expression scope) {
            if (scope instanceof ThisExpr) {
                return Scope.project(typeFqn);
            }
            if (scope instanceof SuperExpr) {
                TypeInfo self = structure.types().get(typeFqn);
                if (self != null && !self.superTypes().isEmpty()) {
                    return typeScope(self.superTypes().get(0));
                }
                return Scope.unknown();
            }
            if (scope instanceof NameExpr n) {
                String name = n.getNameAsString();
                String local = localTypes.get(name);
                if (local != null) {
                    return typeScope(local);
                }
                String field = fieldType(name);
                if (field != null) {
                    return typeScope(field);
                }
                String asType = resolver.resolve(name, typeFqn);
                if (resolver.isProjectType(asType)) {
                    return Scope.project(asType);
                }
                if (!name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    // Math.max, List.of 같은 레포 밖 타입의 정적 호출
                    return Scope.external(name);
                }
                return Scope.unknown();
            }
            if (scope instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
                String field = fieldType(f.getNameAsString());
                return field == null ? Scope.unknown() : typeScope(field);
            }
            if (scope instanceof ObjectCreationExpr oc) {
                return typeScope(resolver.resolve(oc.getType().getNameWithScope(), typeFqn));
            }
            return Scope.unknown();
        }

        private String fieldType(String name) {
            for (TypeInfo t : structure.hierarchy(typeFqn)) {
                String type = t.fields().get(name);
                if (type != null) {
                    return type;
                }
            }
            return null;
        }

        private Scope typeScope(String type) {
            return resolver.isProjectType(type) ? Scope.project(type) : Scope.external(type);
        }
    }

    private record Scope(String type, boolean project) {
        static Scope project(String type) {
            return new Scope(type, true);
        }

        static Scope external(String type) {
            return new Scope(type, false);
        }

        static Scope unknown() {
            return new Scope(null, false);
        }
    }
}
