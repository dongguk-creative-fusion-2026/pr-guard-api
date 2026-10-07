package com.prguard.evidence;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.PullInfo;
import com.prguard.events.EventSink;
import com.prguard.events.Stage;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 바뀐 메서드를 골라 증거 테스트를 만든다 (화면의 EXEC_EVIDENCE 단계).
 *
 * 고르는 기준: 테스트가 아닌 메서드 중 시그니처는 그대로이고 본문만 바뀐 것.
 * 시그니처가 바뀌면 같은 테스트가 base · head 양쪽에서 컴파일될 수 없어 증거가 되지 못한다.
 */
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final int MAX_TYPE_CHARS = 8000;
    private static final int MAX_TEST_CHARS = 6000;

    private final EvidenceGenerator generator;
    private final EvidenceProperties props;

    /** @param generator null 이면 증거 테스트를 만들지 않는다 (LLM 키 · 픽스처 없음) */
    public EvidenceService(EvidenceGenerator generator, EvidenceProperties props) {
        this.generator = generator;
        this.props = props;
    }

    public EvidencePlan plan(AnalysisContext ctx, EventSink sink) {
        List<EvidencePlan.Target> changed = changedTargets(ctx);
        if (!props.on() || generator == null) {
            sink.skipped(Stage.EXEC_EVIDENCE, !props.on() ? "증거 테스트 꺼짐" : "OPENAI_API_KEY 미설정");
            return new EvidencePlan(List.of(), changed);
        }
        Checkout checkout = ctx.checkout().orElse(null);
        if (checkout == null || !ctx.indexed()) {
            sink.skipped(Stage.EXEC_EVIDENCE, "소스 인덱스가 없어 건너뜀");
            return new EvidencePlan(List.of(), changed);
        }
        long started = System.currentTimeMillis();
        List<EvidenceRequest.Target> targets = targets(ctx, checkout);
        if (targets.isEmpty()) {
            sink.skipped(Stage.EXEC_EVIDENCE, "본문만 바뀐 메서드가 없음");
            return new EvidencePlan(List.of(), changed);
        }
        sink.running(Stage.EXEC_EVIDENCE, "바뀐 메서드 " + targets.size() + "개 겨냥 · " + generator.name());
        try {
            PullInfo p = ctx.pull();
            List<EvidenceTest> tests = generator.generate(new EvidenceRequest(p.repo().fullName(), p.number(), p.title(),
                    p.body(), targets));
            sink.done(Stage.EXEC_EVIDENCE, tests.isEmpty() ? "동작이 바뀐 메서드 없음" : "증거 테스트 " + tests.size() + "개",
                    data("generator", generator.name(),
                            "targets", targets.stream().map(EvidenceRequest.Target::methodId).toList(),
                            "tests", tests.stream().map(t -> data("className", t.className(), "path", t.path(),
                                    "target", t.target(), "intent", t.intent(), "code", t.code())).toList(),
                            "ms", System.currentTimeMillis() - started));
            return new EvidencePlan(tests, changed);
        } catch (RuntimeException e) {
            log.warn("증거 테스트 생성 실패: {}", e.toString());
            sink.failed(Stage.EXEC_EVIDENCE, e.getMessage());
            return new EvidencePlan(List.of(), changed);
        }
    }

    /** 호출 기록과 맞춰 볼 바뀐 메서드 (테스트 제외, head 에 있는 것) */
    static List<EvidencePlan.Target> changedTargets(AnalysisContext ctx) {
        List<EvidencePlan.Target> result = new ArrayList<>();
        for (ChangedMethod m : ctx.changedMethods()) {
            if (m.test() || m.id() == null) {
                continue;
            }
            boolean constructor = ctx.headIndex().method(m.id()).map(MethodInfo::constructor).orElse(false);
            result.add(new EvidencePlan.Target(m.id(), m.typeFqn() + "#" + (constructor ? "<init>" : m.name()),
                    m.kind().name(), m.file(), m.line()));
        }
        return result;
    }

    private List<EvidenceRequest.Target> targets(AnalysisContext ctx, Checkout checkout) {
        RepoIndex base = ctx.baseIndex();
        RepoIndex head = ctx.headIndex();
        List<String> libraries = testLibraries(checkout.headDir());
        List<EvidenceRequest.Target> result = new ArrayList<>();
        for (ChangedMethod m : ctx.changedMethods()) {
            if (result.size() >= props.targets()) {
                break;
            }
            if (m.test() || m.kind() != ChangedMethod.Kind.MODIFIED || m.signatureChanged() || !m.bodyChanged()) {
                continue;
            }
            Optional<MethodInfo> h = head.method(m.id());
            Optional<MethodInfo> b = base.method(m.baseId());
            int marker = m.file().indexOf("src/main/java/");
            if (h.isEmpty() || b.isEmpty() || h.get().constructor() || marker < 0) {
                continue;
            }
            String module = m.file().substring(0, marker);
            String dir = m.file().substring(marker + "src/main/java/".length());
            dir = dir.contains("/") ? dir.substring(0, dir.lastIndexOf('/')) : "";
            String simple = EvidenceTest.PREFIX + (result.size() + 1) + "Test";
            String className = (dir.isEmpty() ? "" : dir.replace('/', '.') + ".") + simple;
            String testPath = module + "src/test/java/" + (dir.isEmpty() ? "" : dir + "/") + simple + ".java";
            result.add(new EvidenceRequest.Target(m.id(), m.file(), className, testPath,
                    lines(checkout.baseDir(), b.get()), lines(checkout.headDir(), h.get()),
                    cut(read(checkout.headDir().resolve(m.file())), MAX_TYPE_CHARS),
                    existingTest(head, checkout.headDir(), m.typeFqn()), libraries));
        }
        return result;
    }

    /** 같은 클래스 이름 + Test 인 테스트 클래스 */
    private static String existingTest(RepoIndex head, Path root, String typeFqn) {
        String simple = typeFqn.substring(typeFqn.lastIndexOf('.') + 1);
        return head.types().values().stream()
                .filter(t -> t.test() && (t.simpleName().equals(simple + "Test") || t.simpleName().equals(simple + "Tests")))
                .map(TypeInfo::file)
                .findFirst()
                .map(f -> cut(read(root.resolve(f)), MAX_TEST_CHARS))
                .orElse(null);
    }

    /** 빌드 파일에 적힌 테스트 라이브러리. spring-boot-starter-test 는 JUnit · Mockito · AssertJ 를 함께 가져온다 */
    static List<String> testLibraries(Path root) {
        StringBuilder build = new StringBuilder();
        for (String name : List.of("build.gradle", "build.gradle.kts", "pom.xml")) {
            build.append(read(root.resolve(name)));
        }
        String text = build.toString().toLowerCase(Locale.ROOT);
        List<String> libs = new ArrayList<>(List.of("junit-jupiter"));
        boolean boot = text.contains("spring-boot-starter-test");
        if (boot || text.contains("mockito")) {
            libs.add("mockito");
        }
        if (boot || text.contains("assertj")) {
            libs.add("assertj");
        }
        return libs;
    }

    private static String lines(Path root, MethodInfo m) {
        List<String> all = read(root.resolve(m.file())).lines().toList();
        int from = Math.max(0, m.startLine() - 1);
        int to = Math.min(all.size(), m.endLine());
        return from >= to ? "" : String.join("\n", all.subList(from, to));
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "\n// ... (생략)" : s;
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
