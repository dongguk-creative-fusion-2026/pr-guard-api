package com.prguard.evidence;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * HTTP 동작 diff: PR 이 바꾼 컨트롤러 GET 핸들러에 base · head 에서 같은 요청을 보내 응답을 기록하는 관측 테스트를 만든다.
 * LLM 없이 정한다.
 *
 * 요청은 기존 테스트가 실제로 쓰던 것을 가져온다 (MockMvc {@code get("/owners").param("lastName", "Franklin")}).
 * 없으면 경로 변수를 1 로 채운 기본 요청 하나. 상태를 바꾸지 않게 GET 만 보낸다.
 * 관측 테스트는 @SpringBootTest 로 앱 전체를 띄우고 응답을 상태 · 리다이렉트 · 화면 · 모델 요약 한 줄로 PRGUARD_PROBE_DIR/probe.jsonl 에 쓴다.
 * 결과는 다른 관측 테스트와 같이 {@code BehaviorDiff} 가 맞댄다.
 */
public final class HttpProbePlanner {

    static final int MAX_HANDLERS = 3;
    static final int MAX_REQUESTS = 6;
    private static final Set<String> CONTROLLERS = Set.of("Controller", "RestController");
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    private static final Pattern SAFE = Pattern.compile("[^\\p{Cntrl}\"\\\\]*");

    private HttpProbePlanner() {
    }

    /** GET 요청 하나. params 는 [이름, 값, 이름, 값 …] */
    record Request(String path, List<String> params) {

        String label() {
            StringBuilder sb = new StringBuilder("GET ").append(path);
            for (int i = 0; i + 1 < params.size(); i += 2) {
                sb.append(i == 0 ? '?' : '&').append(params.get(i)).append('=').append(params.get(i + 1));
            }
            return sb.toString();
        }
    }

    /** 바뀐 GET 핸들러와 그 경로 템플릿 */
    record Handler(ChangedMethod method, List<String> paths) {
    }

    /**
     * @param head    head 인덱스
     * @param headDir head 소스 (기존 테스트에서 요청을 가져온다)
     * @param boot    빌드에 spring-boot-starter-test 가 있나 (없으면 만들지 않는다)
     */
    public static List<EvidenceTest> plan(List<ChangedMethod> changed, RepoIndex head, Path headDir, boolean boot) {
        if (!boot) {
            return List.of();
        }
        Optional<TypeInfo> app = head.types().values().stream()
                .filter(t -> !t.test() && t.annotations().stream().anyMatch(a -> simple(a).equals("SpringBootApplication")))
                .findFirst();
        if (app.isEmpty()) {
            return List.of();
        }
        List<Handler> handlers = handlers(changed, head);
        if (handlers.isEmpty()) {
            return List.of();
        }
        List<Request> harvested = harvest(headDir);
        String appFqn = app.get().fqn();
        String pkg = appFqn.contains(".") ? appFqn.substring(0, appFqn.lastIndexOf('.')) : "";
        String file = app.get().file();
        int marker = file.indexOf("src/main/java/");
        String module = marker < 0 ? "" : file.substring(0, marker);
        List<EvidenceTest> out = new ArrayList<>();
        for (Handler h : handlers) {
            List<Request> requests = requestsFor(h, harvested);
            if (requests.isEmpty()) {
                continue;
            }
            String simple = EvidenceTest.PROBE_PREFIX + "Http" + (out.size() + 1) + "Test";
            String className = (pkg.isEmpty() ? "" : pkg + ".") + simple;
            String path = module + "src/test/java/" + (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/") + simple + ".java";
            out.add(new EvidenceTest(path, className, h.method().id(),
                    "같은 HTTP 요청에 대한 응답 (" + String.join(", ", h.paths()) + ")", source(pkg, simple, requests)));
        }
        return out;
    }

    /** PR 이 본문을 바꾸거나 새로 만든 컨트롤러 GET 핸들러 */
    static List<Handler> handlers(List<ChangedMethod> changed, RepoIndex head) {
        List<Handler> out = new ArrayList<>();
        for (ChangedMethod m : changed) {
            if (out.size() >= MAX_HANDLERS) {
                break;
            }
            if (m.test() || m.id() == null || m.kind() == ChangedMethod.Kind.REMOVED) {
                continue;
            }
            TypeInfo type = head.types().get(m.typeFqn());
            MethodInfo info = head.method(m.id()).orElse(null);
            if (type == null || info == null
                    || type.annotations().stream().noneMatch(a -> CONTROLLERS.contains(simple(a)))) {
                continue;
            }
            List<String> prefixes = new ArrayList<>();
            for (String a : type.annotations()) {
                if (simple(a).equals("RequestMapping")) {
                    prefixes.addAll(paths(a));
                }
            }
            if (prefixes.isEmpty()) {
                prefixes.add("");
            }
            List<String> paths = new ArrayList<>();
            for (String a : info.annotations()) {
                if (!getMapping(a)) {
                    continue;
                }
                List<String> own = paths(a);
                for (String p : prefixes) {
                    for (String o : own.isEmpty() ? List.of("") : own) {
                        paths.add(join(p, o));
                    }
                }
            }
            if (!paths.isEmpty()) {
                out.add(new Handler(m, paths.stream().distinct().toList()));
            }
        }
        return out;
    }

    /** @GetMapping 이거나 GET 인 @RequestMapping (method 를 안 적으면 GET 도 받는다) */
    private static boolean getMapping(String annotation) {
        String name = simple(annotation);
        if (name.equals("GetMapping")) {
            return true;
        }
        return name.equals("RequestMapping") && (!annotation.contains("method") || annotation.contains("GET"));
    }

    /** 어노테이션 원문에서 value · path 의 문자열들 */
    static List<String> paths(String annotation) {
        List<String> out = new ArrayList<>();
        try {
            AnnotationExpr a = PARSER.parseAnnotation(annotation).getResult().orElse(null);
            if (a instanceof SingleMemberAnnotationExpr s) {
                strings(s.getMemberValue(), out);
            } else if (a instanceof NormalAnnotationExpr n) {
                for (MemberValuePair p : n.getPairs()) {
                    if (p.getNameAsString().equals("value") || p.getNameAsString().equals("path")) {
                        strings(p.getValue(), out);
                    }
                }
            }
        } catch (RuntimeException e) {
            // 읽지 못하면 경로 없음
        }
        return out;
    }

    private static void strings(Expression e, List<String> out) {
        if (e instanceof StringLiteralExpr s) {
            out.add(s.getValue());
        } else if (e instanceof ArrayInitializerExpr arr) {
            arr.getValues().forEach(v -> strings(v, out));
        }
    }

    /** 이 핸들러 경로에 맞는 기존 테스트 요청들. 없으면 경로 변수를 1 로 채운 기본 요청 */
    static List<Request> requestsFor(Handler h, List<Request> harvested) {
        Set<Request> out = new LinkedHashSet<>();
        for (String template : h.paths()) {
            Pattern match = Pattern.compile(template.replaceAll("\\{[^/}]+}", "[^/]+").replace(".", "\\.") + "/?");
            for (Request r : harvested) {
                if (r.path().equals(template) || match.matcher(r.path()).matches()) {
                    out.add(new Request(fill(r.path()), r.params()));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new Request(fill(h.paths().get(0)), List.of()));
        }
        return out.stream().filter(r -> safe(r)).limit(MAX_REQUESTS).toList();
    }

    /** 기존 테스트의 MockMvc GET 요청: get("/path?a=1").param("k", "v") — 문자열 상수만 쓴다 */
    static List<Request> harvest(Path root) {
        List<Request> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root, 20)) {
            files = walk.filter(p -> p.toString().replace('\\', '/').contains("src/test/java/")
                            && p.toString().endsWith(".java") && !p.getFileName().toString().startsWith(EvidenceTest.PROBE_PREFIX))
                    .limit(500).toList();
        } catch (IOException | RuntimeException e) {
            return out;
        }
        for (Path f : files) {
            String src;
            try {
                src = Files.readString(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            if (!src.contains("get(")) {
                continue;
            }
            try {
                PARSER.parse(src).getResult().ifPresent(cu -> cu.findAll(MethodCallExpr.class).forEach(call -> {
                    if (!call.getNameAsString().equals("get") || call.getArguments().isEmpty()
                            || !(call.getArgument(0) instanceof StringLiteralExpr path)
                            || !path.getValue().startsWith("/")) {
                        return;
                    }
                    String raw = path.getValue();
                    List<String> params = new ArrayList<>();
                    int q = raw.indexOf('?');
                    if (q >= 0) {
                        for (String kv : raw.substring(q + 1).split("&")) {
                            String[] p = kv.split("=", 2);
                            params.add(p[0]);
                            params.add(p.length > 1 ? p[1] : "");
                        }
                        raw = raw.substring(0, q);
                    }
                    // 경로 템플릿 변수에 넘긴 값: get("/owners/{ownerId}", 1) → /owners/1
                    raw = expand(raw, call.getArguments().subList(1, call.getArguments().size()));
                    params.addAll(chainedParams(call));
                    out.add(new Request(raw, List.copyOf(params)));
                }));
            } catch (RuntimeException e) {
                // 읽지 못한 파일은 건너뛴다
            }
        }
        return out;
    }

    /** get(...).param("k", "v").param(...) 의 문자열 상수 파라미터 (값이 상수가 아니면 그 파라미터는 뺀다) */
    private static List<String> chainedParams(MethodCallExpr get) {
        List<String> out = new ArrayList<>();
        Node node = get;
        while (node.getParentNode().orElse(null) instanceof MethodCallExpr parent
                && parent.getScope().orElse(null) == node) {
            if (parent.getNameAsString().equals("param") && parent.getArguments().size() == 2
                    && parent.getArgument(0) instanceof StringLiteralExpr k && parent.getArgument(1) instanceof StringLiteralExpr v) {
                out.add(k.getValue());
                out.add(v.getValue());
            }
            node = parent;
        }
        return out;
    }

    private static String expand(String template, List<Expression> args) {
        String out = template;
        for (Expression a : args) {
            if (!out.contains("{")) {
                break;
            }
            String value = a instanceof IntegerLiteralExpr i ? i.getValue()
                    : a instanceof StringLiteralExpr s ? s.getValue() : null;
            out = out.replaceFirst("\\{[^/}]+}", value == null ? "1" : java.util.regex.Matcher.quoteReplacement(value));
        }
        return out;
    }

    /** 남은 경로 변수는 1 로 */
    private static String fill(String path) {
        String p = path.replaceAll("\\{[^/}]+}", "1");
        return p.isEmpty() ? "/" : p;
    }

    private static boolean safe(Request r) {
        return SAFE.matcher(r.path()).matches() && r.params().stream().allMatch(p -> SAFE.matcher(p).matches());
    }

    private static String join(String prefix, String path) {
        String a = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String b = path.isEmpty() || path.startsWith("/") ? path : "/" + path;
        String joined = a + b;
        return joined.isEmpty() ? "/" : joined;
    }

    /** "@org.x.GetMapping(\"/a\")" → GetMapping */
    private static String simple(String annotation) {
        String name = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        int paren = name.indexOf('(');
        if (paren >= 0) {
            name = name.substring(0, paren);
        }
        return name.substring(name.lastIndexOf('.') + 1).strip();
    }

    private static String lit(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** 관측 테스트 소스. 앱 패키지에 둬야 @SpringBootTest 가 설정을 찾는다 */
    static String source(String pkg, String simple, List<Request> requests) {
        StringBuilder calls = new StringBuilder();
        for (Request r : requests) {
            calls.append("        call(mvc, ").append(lit(r.label())).append(", ").append(lit(r.path()));
            for (String p : r.params()) {
                calls.append(", ").append(lit(p));
            }
            calls.append(");\n");
        }
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("PACKAGE", pkg.isEmpty() ? "" : "package " + pkg + ";\n");
        vars.put("CLASS", simple);
        vars.put("CALLS", calls.toString());
        String code = TEMPLATE;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            code = code.replace("${" + e.getKey() + "}", e.getValue());
        }
        return code;
    }

    private static final String TEMPLATE = """
            ${PACKAGE}
            import java.io.IOException;
            import java.lang.reflect.Method;
            import java.nio.charset.StandardCharsets;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import java.util.Map;
            import java.util.TreeMap;
            import org.junit.jupiter.api.Test;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.boot.test.context.SpringBootTest;
            import org.springframework.test.web.servlet.MockMvc;
            import org.springframework.test.web.servlet.MvcResult;
            import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
            import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
            import org.springframework.test.web.servlet.setup.MockMvcBuilders;
            import org.springframework.web.context.WebApplicationContext;
            import org.springframework.web.servlet.ModelAndView;

            /** PR Guard HTTP 동작 diff: 바뀐 컨트롤러에 base · head 에서 같은 요청을 보내 응답을 기록한다 (판정하지 않음) */
            @SpringBootTest
            class ${CLASS} {

                @Autowired
                WebApplicationContext context;

                @Test
                void http() {
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            ${CALLS}    }

                private void call(MockMvc mvc, String label, String path, String... params) {
                    String out;
                    try {
                        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.get(path);
                        for (int i = 0; i + 1 < params.length; i += 2) {
                            req.param(params[i], params[i + 1]);
                        }
                        out = summary(mvc.perform(req).andReturn());
                    } catch (Throwable t) {
                        Throwable cause = t.getCause() != null ? t.getCause() : t;
                        out = "throws " + cause.getClass().getSimpleName();
                    }
                    write(label, out);
                }

                /** 상태 · 리다이렉트 · 화면 · 모델 요약. 실행마다 달라지는 값(해시 · 시각)은 쓰지 않는다 */
                static String summary(MvcResult r) throws Exception {
                    StringBuilder sb = new StringBuilder(String.valueOf(r.getResponse().getStatus()));
                    String redirect = r.getResponse().getRedirectedUrl();
                    if (redirect != null) {
                        sb.append(" → ").append(redirect);
                    }
                    ModelAndView mv = r.getModelAndView();
                    if (mv != null && redirect == null && mv.getViewName() != null) {
                        sb.append(" 화면 ").append(mv.getViewName());
                    }
                    if (mv != null && redirect == null) {
                        List<String> parts = new ArrayList<>();
                        for (Map.Entry<String, Object> e : new TreeMap<>(mv.getModel()).entrySet()) {
                            if (e.getKey().startsWith("org.springframework") || parts.size() >= 5) {
                                continue;
                            }
                            parts.add(e.getKey() + "=" + describe(e.getValue()));
                        }
                        if (!parts.isEmpty()) {
                            sb.append(" · ").append(String.join(", ", parts));
                        }
                    }
                    if (r.getResolvedException() != null) {
                        sb.append(" 예외 ").append(r.getResolvedException().getClass().getSimpleName());
                    }
                    String type = r.getResponse().getContentType();
                    if (mv == null && type != null && type.contains("json")) {
                        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
                        sb.append(" ").append(body.length() > 120 ? body.substring(0, 120) + "…" : body);
                    }
                    return sb.toString();
                }

                static String describe(Object v) {
                    if (v == null) {
                        return "null";
                    }
                    if (v instanceof Collection<?> c) {
                        return c.size() + "개";
                    }
                    if (v instanceof Map<?, ?> m) {
                        return m.size() + "개";
                    }
                    if (v instanceof CharSequence || v instanceof Number || v instanceof Boolean || v instanceof Enum<?>) {
                        String s = String.valueOf(v);
                        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
                    }
                    // Spring Data Page · Slice
                    for (String name : new String[] {"getNumberOfElements", "getTotalElements"}) {
                        try {
                            Method method = v.getClass().getMethod(name);
                            return method.invoke(v) + "개";
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // 다음 방법
                        }
                    }
                    return v.getClass().getSimpleName().replaceAll("\\\\$.*", "");
                }

                private static void write(String label, String out) {
                    String dir = System.getenv("PRGUARD_PROBE_DIR");
                    Path file = Path.of(dir == null || dir.isBlank() ? "build/prguard-probe" : dir, "probe.jsonl");
                    String line = "{\\"probe\\":" + quote("${CLASS}") + ",\\"label\\":" + quote(label) + ",\\"out\\":" + quote(out) + "}\\n";
                    try {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }

                private static String quote(String s) {
                    StringBuilder sb = new StringBuilder("\\"");
                    for (char c : s.toCharArray()) {
                        switch (c) {
                            case '"' -> sb.append("\\\\\\"");
                            case '\\\\' -> sb.append("\\\\\\\\");
                            case '\\n' -> sb.append("\\\\n");
                            default -> sb.append(c < 0x20 ? ' ' : c);
                        }
                    }
                    return sb.append('"').toString();
                }
            }
            """;
}
