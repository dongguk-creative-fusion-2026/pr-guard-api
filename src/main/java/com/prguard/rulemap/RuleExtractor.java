package com.prguard.rulemap;

import com.prguard.index.CallSite;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import com.prguard.rulemap.Rule.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 레포 인덱스와 소스에서 규칙을 뽑는다. LLM 은 쓰지 않는다.
 *
 * <ul>
 *   <li>사실: 메서드가 던지는 예외(HTTP 상태 포함) · null 반환 · 부르는 검사 · 권한 어노테이션 · 정렬 쿼리 · 원자적 갱신,
 *       엔티티 필드 제약(NOT NULL · 길이 · null 가능)</li>
 *   <li>관례: 같은 종류의 코드 대부분(75% 이상, 2곳 이상)이 따르는 사실. 서비스 수정·삭제는 검사를 부른다,
 *       조회는 null 대신 예외를 던진다, 쓰기 API 는 권한 어노테이션을 단다, 응답 DTO 는 민감 필드를 담지 않는다</li>
 * </ul>
 */
public final class RuleExtractor {

    /** 뽑는 방식이 바뀌면 올린다. 저장해 둔 지도는 같은 버전만 다시 쓴다 */
    public static final int VERSION = 2;

    public static final String WRITE_GUARD = "WRITE_GUARD";
    public static final String LOOKUP_THROWS = "LOOKUP_THROWS";
    public static final String WRITE_AUTH = "WRITE_AUTH";
    public static final String RESPONSE_NO_SENSITIVE = "RESPONSE_NO_SENSITIVE";

    private static final double MIN_CONVENTION_RATIO = 0.75;
    private static final int MIN_CONVENTION_TOTAL = 2;
    private static final int MAX_MEMBERS = 60;

    private static final Pattern NEW_EXCEPTION = Pattern.compile("\\bnew\\s+([A-Z]\\w*(?:Exception|Error))\\s*\\(");
    private static final Pattern RETURNS_NULL = Pattern.compile("\\breturn\\s+null\\s*;|\\.orElse\\(\\s*null\\s*\\)");
    private static final Pattern ATOMIC = Pattern.compile("(?i)\\bset\\s+([\\w.]+)\\s*=\\s*\\1\\s*[+-]");
    private static final Pattern ORDER_BY_QUERY = Pattern.compile("(?i)\\border\\s+by\\s+([\\w.]+(?:\\s+(?:asc|desc))?)");
    private static final Pattern ORDER_BY_NAME = Pattern.compile("OrderBy(\\w+?)(Asc|Desc)?$");
    private static final Pattern GUARD_NAME = Pattern.compile("^(require|check|verify|assert|validate|ensure)[A-Z]\\w*");
    private static final List<String> GUARD_TYPES = List.of("Guard", "Policy", "Validator", "Checker");
    private static final Pattern WRITE_NAME = Pattern.compile("^(delete|remove|update|edit|change|modify)\\w*");
    private static final Pattern LOOKUP_NAME = Pattern.compile("^(get|find|load|fetch)([A-Z]\\w*)?$");
    private static final Set<String> AUTH_ANNOTATIONS = Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed");
    private static final Set<String> WRITE_MAPPINGS = Set.of("PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static final Set<String> SENSITIVE = Set.of("email", "password", "passwordHash", "phone", "phoneNumber",
            "ssn", "token", "secret");
    private static final Set<String> NON_NULL_TYPES = Set.of("int", "long", "short", "byte", "boolean", "double",
            "float", "char", "List", "Set", "Map", "Collection");
    private static final Pattern STATUS = Pattern.compile("HttpStatus\\.(\\w+)");
    private static final Map<String, Integer> HTTP = Map.of("BAD_REQUEST", 400, "UNAUTHORIZED", 401, "FORBIDDEN", 403,
            "NOT_FOUND", 404, "CONFLICT", 409, "UNPROCESSABLE_ENTITY", 422);
    /** 레포 밖 예외 중 상태가 정해진 것 */
    private static final Map<String, Integer> KNOWN_EXCEPTIONS = Map.of("AccessDeniedException", 403);

    private RuleExtractor() {
    }

    public static List<Rule> extract(RepoIndex index, Path root) {
        Sources src = new Sources(root);
        Map<String, List<CallSite>> callsByCaller = index.calls().stream()
                .collect(Collectors.groupingBy(CallSite::callerId, LinkedHashMap::new, Collectors.toList()));
        List<Rule> rules = new ArrayList<>();
        for (MethodInfo m : sorted(index.methods().values())) {
            if (!m.test() && !m.implicit()) {
                rules.addAll(facts(m, index, src, callsByCaller));
            }
        }
        for (TypeInfo t : index.types().values()) {
            if (!t.test() && t.annotations().stream().anyMatch(a -> a.startsWith("@Entity"))) {
                rules.addAll(fieldRules(t, src));
            }
        }
        rules.addAll(conventions(index, rules, callsByCaller));
        return rules;
    }

    // ── 사실 ─────────────────────────────────────────────────────────

    private static List<Rule> facts(MethodInfo m, RepoIndex index, Sources src, Map<String, List<CallSite>> calls) {
        List<Rule> result = new ArrayList<>();
        String name = shortId(m.id());
        String body = src.text(m.file(), m.startLine(), m.endLine());
        Set<String> thrown = new LinkedHashSet<>();
        Matcher ex = NEW_EXCEPTION.matcher(body);
        while (ex.find()) {
            thrown.add(ex.group(1));
        }
        for (String exception : thrown) {
            Integer status = httpStatus(exception, index);
            String when = body.contains("orElseThrow") ? "찾는 값이 없으면 " : "";
            result.add(Rule.fact(Kind.THROWS, m.id(), name + ": " + when + exception + " 예외를 던진다"
                    + (status != null ? " → HTTP " + status : ""), exception, m.file(), m.startLine()));
        }
        if (RETURNS_NULL.matcher(body).find()) {
            result.add(Rule.fact(Kind.RETURNS_NULL, m.id(), name + ": null 을 돌려줄 수 있다", "null", m.file(),
                    m.startLine()));
        }
        // 오버로드는 인자 수만 맞춰 여러 개로 연결되므로 이름으로 묶는다
        Set<String> guards = new LinkedHashSet<>();
        guardCalls(m, index, calls).forEach(g -> guards.add(guardName(g)));
        for (String guard : guards) {
            result.add(Rule.fact(Kind.GUARD, m.id(), name + ": " + guard + " 로 검사한다", guard, m.file(),
                    m.startLine()));
        }
        for (String a : m.annotations()) {
            if (AUTH_ANNOTATIONS.contains(annotationName(a))) {
                result.add(Rule.fact(Kind.AUTH, m.id(), name + ": 권한 검사 " + a, a, m.file(), m.startLine()));
            }
        }
        String query = m.annotations().stream().filter(a -> a.startsWith("@Query")).findFirst().orElse("");
        Matcher order = ORDER_BY_QUERY.matcher(query);
        Matcher orderName = ORDER_BY_NAME.matcher(m.name());
        if (order.find()) {
            result.add(Rule.fact(Kind.ORDERED, m.id(), name + ": " + order.group(1) + " 순으로 정렬해서 돌려준다",
                    order.group(1), m.file(), m.startLine()));
        } else if (orderName.find()) {
            String by = orderName.group(1) + (orderName.group(2) != null ? " " + orderName.group(2) : "");
            result.add(Rule.fact(Kind.ORDERED, m.id(), name + ": " + by + " 순으로 정렬해서 돌려준다", by, m.file(),
                    m.startLine()));
        }
        Matcher atomic = ATOMIC.matcher(query);
        if (atomic.find()) {
            result.add(Rule.fact(Kind.ATOMIC_UPDATE, m.id(), name + ": DB 에서 " + atomic.group(1)
                    + " 를 원자적으로 바꾼다 (동시 요청에도 값이 유실되지 않음)", atomic.group(1), m.file(), m.startLine()));
        }
        return result;
    }

    private static Integer httpStatus(String exception, RepoIndex index) {
        Optional<TypeInfo> type = index.types().values().stream()
                .filter(t -> t.simpleName().equals(exception))
                .findFirst();
        if (type.isEmpty()) {
            return KNOWN_EXCEPTIONS.get(exception);
        }
        for (String a : type.get().annotations()) {
            Matcher s = STATUS.matcher(a);
            if (a.startsWith("@ResponseStatus") && s.find()) {
                return HTTP.get(s.group(1));
            }
        }
        return null;
    }

    /** 엔티티 필드: 선언 위 어노테이션으로 NOT NULL · 길이 · null 가능을 정한다 */
    private static List<Rule> fieldRules(TypeInfo t, Sources src) {
        List<Rule> result = new ArrayList<>();
        List<String> lines = src.lines(t.file());
        for (Map.Entry<String, String> field : t.fields().entrySet()) {
            String name = field.getKey();
            Pattern decl = Pattern.compile("^\\s*(?:private|protected|public)?\\s*(?:final\\s+)?[\\w<>,.?\\[\\] ]+\\s+"
                    + Pattern.quote(name) + "\\s*(?:=|;)");
            int at = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (decl.matcher(lines.get(i)).find() && !lines.get(i).contains("(")) {
                    at = i;
                    break;
                }
            }
            if (at < 0) {
                continue;
            }
            StringBuilder annotations = new StringBuilder();
            for (int i = at - 1; i >= 0 && lines.get(i).strip().startsWith("@"); i--) {
                annotations.insert(0, lines.get(i).strip() + " ");
            }
            String a = annotations.toString();
            if (a.contains("@Id") || a.contains("ToMany") || a.contains("@Transient")) {
                continue;
            }
            String subject = t.fqn() + "." + name;
            String label = t.simpleName() + "." + name;
            List<String> parts = new ArrayList<>();
            boolean notNull = a.matches(".*nullable\\s*=\\s*false.*") || a.contains("@NotNull") || a.contains("@NotBlank")
                    || a.matches(".*optional\\s*=\\s*false.*");
            Matcher length = Pattern.compile("(?:length|max)\\s*=\\s*(\\d+)").matcher(a);
            String type = simpleType(field.getValue());
            if (notNull) {
                parts.add("NOT NULL");
            } else if (!NON_NULL_TYPES.contains(type)) {
                parts.add("null 일 수 있다");
            }
            if (length.find()) {
                parts.add("최대 " + length.group(1) + "자");
            }
            if (!parts.isEmpty()) {
                result.add(Rule.fact(Kind.FIELD, subject, label + ": " + String.join(", ", parts),
                        notNull ? "NOT_NULL" : (parts.get(0).startsWith("null") ? "NULLABLE" : "LENGTH"), t.file(), at + 1));
            }
        }
        return result;
    }

    // ── 관례 ─────────────────────────────────────────────────────────

    private static List<Rule> conventions(RepoIndex index, List<Rule> facts, Map<String, List<CallSite>> calls) {
        Map<String, List<Rule>> bySubject = facts.stream().collect(Collectors.groupingBy(Rule::subject));
        List<Rule> result = new ArrayList<>();
        List<MethodInfo> methods = sorted(index.methods().values()).stream()
                .filter(m -> !m.test() && !m.implicit() && !m.constructor())
                .toList();

        List<MethodInfo> writes = methods.stream().filter(m -> isWriteServiceMethod(m, index)).toList();
        List<MethodInfo> guarded = writes.stream().filter(m -> has(bySubject, m, Kind.GUARD)).toList();
        String guard = mostCommon(guarded.stream()
                .flatMap(m -> bySubject.get(m.id()).stream().filter(r -> r.kind() == Kind.GUARD))
                .map(Rule::detail).toList());
        convention(WRITE_GUARD, "서비스의 수정·삭제 메서드는 검사를 부른다" + (guard != null ? " (예: " + guard + ")" : ""),
                guard, guarded, writes, MethodInfo::id, result);

        List<MethodInfo> lookups = methods.stream().filter(m -> isLookupServiceMethod(m, index)).toList();
        List<MethodInfo> throwing = lookups.stream()
                .filter(m -> has(bySubject, m, Kind.THROWS) && !has(bySubject, m, Kind.RETURNS_NULL))
                .toList();
        boolean anyNull = lookups.stream().anyMatch(m -> has(bySubject, m, Kind.RETURNS_NULL));
        String exception = mostCommon(throwing.stream()
                .flatMap(m -> bySubject.get(m.id()).stream().filter(r -> r.kind() == Kind.THROWS))
                .map(Rule::detail).toList());
        if (!anyNull) {
            convention(LOOKUP_THROWS, "서비스의 조회 메서드는 찾는 값이 없으면 null 대신 예외를 던진다"
                    + (exception != null ? " (예: " + exception + ")" : ""), exception, throwing, lookups, MethodInfo::id,
                    result);
        }

        List<MethodInfo> endpoints = methods.stream()
                .filter(m -> index.types().containsKey(m.typeFqn()) && isWriteEndpoint(m, index.types().get(m.typeFqn())))
                .toList();
        List<MethodInfo> authed = endpoints.stream().filter(m -> hasAuth(m, index.types().get(m.typeFqn()))).toList();
        String auth = mostCommon(authed.stream()
                .flatMap(m -> m.annotations().stream().filter(a -> AUTH_ANNOTATIONS.contains(annotationName(a))))
                .toList());
        convention(WRITE_AUTH, "쓰기 API(POST·PUT·PATCH·DELETE)는 권한 어노테이션을 단다" + (auth != null ? " (예: " + auth + ")" : ""),
                auth, authed, endpoints, MethodInfo::id, result);

        List<TypeInfo> responses = index.types().values().stream()
                .filter(t -> !t.test() && isResponseType(t))
                .sorted(Comparator.comparing(TypeInfo::fqn))
                .toList();
        List<TypeInfo> clean = responses.stream().filter(t -> sensitiveFields(t).isEmpty()).toList();
        if (clean.size() == responses.size()) {
            convention(RESPONSE_NO_SENSITIVE, "응답 DTO 는 email · password 같은 민감 필드를 담지 않는다",
                    String.join(",", SENSITIVE.stream().sorted().toList()), clean, responses, TypeInfo::fqn, result);
        }
        return result;
    }

    private static <T> void convention(String key, String statement, String detail, List<T> following, List<T> all,
                                       Function<T, String> id, List<Rule> out) {
        if (all.size() < MIN_CONVENTION_TOTAL || (double) following.size() / all.size() < MIN_CONVENTION_RATIO) {
            return;
        }
        out.add(new Rule(Kind.CONVENTION.name() + "|" + key, Kind.CONVENTION, Rule.Origin.CONVENTION, key, statement,
                detail, null, 0, following.size(), all.size(), following.stream().map(id).limit(MAX_MEMBERS).toList(),
                all.stream().filter(x -> !following.contains(x)).map(id).limit(MAX_MEMBERS).toList()));
    }

    private static boolean has(Map<String, List<Rule>> bySubject, MethodInfo m, Kind kind) {
        return bySubject.getOrDefault(m.id(), List.of()).stream().anyMatch(r -> r.kind() == kind);
    }

    private static String mostCommon(List<String> values) {
        return values.stream()
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // ── 관례가 적용되는 코드 (관례 검사기도 같은 기준을 쓴다) ──────────

    /** 서비스(이름이 Service 로 끝나는 클래스)의 public 수정·삭제 메서드 */
    public static boolean isWriteServiceMethod(MethodInfo m, RepoIndex index) {
        return !m.constructor() && m.modifiers().contains("public") && isService(m, index)
                && WRITE_NAME.matcher(m.name()).matches();
    }

    /** 서비스의 public 조회 메서드 중 값 하나를 돌려주는 것 (목록 · Optional 제외) */
    public static boolean isLookupServiceMethod(MethodInfo m, RepoIndex index) {
        String ret = simpleType(m.returnType());
        return !m.constructor() && m.modifiers().contains("public") && isService(m, index)
                && LOOKUP_NAME.matcher(m.name()).matches() && !m.paramTypes().isEmpty()
                && !ret.isEmpty() && !ret.equals("void") && !ret.equals("Optional") && !ret.equals("Page")
                && !NON_NULL_TYPES.contains(ret);
    }

    /** 컨트롤러의 쓰기 매핑 (POST · PUT · PATCH · DELETE) */
    public static boolean isWriteEndpoint(MethodInfo m, TypeInfo type) {
        boolean controller = type.annotations().stream()
                .anyMatch(a -> a.startsWith("@RestController") || a.startsWith("@Controller"));
        return controller && m.annotations().stream().anyMatch(a -> WRITE_MAPPINGS.contains(annotationName(a)));
    }

    /** 컨트롤러의 요청 매핑 (GET 포함) */
    public static boolean isEndpoint(MethodInfo m, TypeInfo type) {
        boolean controller = type.annotations().stream()
                .anyMatch(a -> a.startsWith("@RestController") || a.startsWith("@Controller"));
        return controller && m.annotations().stream().anyMatch(a -> annotationName(a).endsWith("Mapping"));
    }

    /** 메서드가 돌려주는 레포 안 타입 (List&lt;X&gt; · Page&lt;X&gt; 면 X) */
    public static Optional<TypeInfo> returnedType(MethodInfo m, RepoIndex index) {
        String ret = m.returnType() == null ? "" : m.returnType();
        Matcher generic = Pattern.compile("<\\s*([\\w.]+)\\s*>").matcher(ret);
        String name = simpleType(generic.find() ? generic.group(1) : ret);
        return index.types().values().stream().filter(t -> !t.test() && t.simpleName().equals(name)).findFirst();
    }

    /** 메서드나 클래스에 권한 어노테이션이 있는가 */
    public static boolean hasAuth(MethodInfo m, TypeInfo type) {
        return m.annotations().stream().anyMatch(a -> AUTH_ANNOTATIONS.contains(annotationName(a)))
                || type != null && type.annotations().stream().anyMatch(a -> AUTH_ANNOTATIONS.contains(annotationName(a)));
    }

    public static boolean isResponseType(TypeInfo t) {
        String n = t.simpleName();
        return n.endsWith("Response") || n.endsWith("Dto") || n.endsWith("DTO") || n.endsWith("View");
    }

    public static List<String> sensitiveFields(TypeInfo t) {
        return t.fields().keySet().stream().filter(SENSITIVE::contains).toList();
    }

    /** 이 메서드가 부르는 검사: 이름이 Guard · Policy · Validator 로 끝나는 레포 안 타입의 메서드, 또는 require · check … 로 시작하는 메서드 */
    public static List<MethodInfo> guardCalls(MethodInfo m, RepoIndex index, Map<String, List<CallSite>> calls) {
        List<MethodInfo> result = new ArrayList<>();
        for (CallSite c : calls.getOrDefault(m.id(), List.of())) {
            if (c.resolution() != CallSite.Resolution.RESOLVED) {
                continue;
            }
            for (String id : c.calleeIds()) {
                index.method(id).filter(callee -> !callee.test() && isGuard(callee, index))
                        .filter(callee -> result.stream().noneMatch(r -> r.id().equals(callee.id())))
                        .ifPresent(result::add);
            }
        }
        return result;
    }

    public static List<MethodInfo> guardCalls(MethodInfo m, RepoIndex index) {
        return guardCalls(m, index, Map.of(m.id(), index.calls().stream().filter(c -> c.callerId().equals(m.id())).toList()));
    }

    /** OwnershipGuard#requireOwner (파라미터 없이) */
    public static String guardName(MethodInfo guard) {
        String id = shortId(guard.id());
        int paren = id.indexOf('(');
        return paren < 0 ? id : id.substring(0, paren);
    }

    private static boolean isGuard(MethodInfo callee, RepoIndex index) {
        TypeInfo type = index.types().get(callee.typeFqn());
        String typeName = type == null ? "" : type.simpleName();
        return GUARD_TYPES.stream().anyMatch(typeName::endsWith) || GUARD_NAME.matcher(callee.name()).matches();
    }

    private static boolean isService(MethodInfo m, RepoIndex index) {
        TypeInfo type = index.types().get(m.typeFqn());
        return type != null && type.kind().equals("class") && type.simpleName().endsWith("Service");
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private static List<MethodInfo> sorted(java.util.Collection<MethodInfo> methods) {
        return methods.stream().sorted(Comparator.comparing(MethodInfo::id)).toList();
    }

    private static String annotationName(String annotation) {
        String a = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        int paren = a.indexOf('(');
        String name = paren < 0 ? a : a.substring(0, paren);
        return name.substring(name.lastIndexOf('.') + 1).strip();
    }

    static String simpleType(String type) {
        if (type == null) {
            return "";
        }
        String t = type.replaceAll("<.*>", "").replace("...", "").replace("[]", "").strip();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    /** com.a.Post#getTitle() → Post#getTitle() */
    public static String shortId(String id) {
        int hash = id.indexOf('#');
        String type = hash < 0 ? id : id.substring(0, hash);
        int dot = type.lastIndexOf('.');
        return type.substring(dot + 1) + (hash < 0 ? "" : id.substring(hash));
    }

    private static final class Sources {
        private final Path root;
        private final Map<String, List<String>> files = new HashMap<>();

        Sources(Path root) {
            this.root = root;
        }

        List<String> lines(String file) {
            return files.computeIfAbsent(file, f -> {
                try {
                    return Files.readAllLines(root.resolve(f), StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    return List.of();
                }
            });
        }

        String text(String file, int from, int to) {
            List<String> lines = lines(file);
            int start = Math.max(0, from - 1);
            int end = Math.min(lines.size(), to);
            return start >= end ? "" : String.join("\n", lines.subList(start, end));
        }
    }
}
