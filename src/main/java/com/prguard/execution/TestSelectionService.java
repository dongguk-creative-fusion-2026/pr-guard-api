package com.prguard.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.AnalysisContext;
import com.prguard.events.EventSink;
import com.prguard.events.Stage;
import com.prguard.execution.JUnitReportParser.TestCase;
import com.prguard.execution.TestRunRepository.ReferenceRun;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 영향 테스트 선택을 분석 결과 · 기준 기록과 묶는다. 계산은 {@link TestSelector}, 여기는 재료 모으기와 단계 기록.
 */
@Service
public class TestSelectionService {

    private static final Logger log = LoggerFactory.getLogger(TestSelectionService.class);
    /** 단계 기록에 싣는 테스트 클래스 수 (전체는 실행 기록의 selection 에 있다) */
    private static final int MAX_EVENT_TESTS = 300;

    private final TestSelectionProperties props;
    private final TestRunRepository runs;
    private final ObjectMapper mapper;

    public TestSelectionService(TestSelectionProperties props, TestRunRepository runs, ObjectMapper mapper) {
        this.props = props;
        this.runs = runs;
        this.mapper = mapper;
    }

    public TestSelection select(Long reviewId, AnalysisContext ctx, EventSink sink) {
        if (props.mode() == TestSelectionProperties.Mode.OFF) {
            sink.skipped(Stage.EXEC_SELECT, "테스트 선택 꺼짐 — 전체 실행");
            return TestSelection.off("테스트 선택 꺼짐");
        }
        Checkout checkout = ctx.checkout().orElse(null);
        if (checkout == null || !ctx.indexed()) {
            sink.skipped(Stage.EXEC_SELECT, "소스 인덱스가 없어 전체 실행");
            return TestSelection.off("소스 인덱스 없음");
        }
        long started = System.currentTimeMillis();
        sink.running(Stage.EXEC_SELECT, "바뀐 메서드 " + ctx.changedMethods().size() + "개 · 기준 기록 찾는 중");
        try {
            Optional<ReferenceRun> ref = reviewId == null ? Optional.empty() : runs.reference(reviewId, checkout.baseSha());
            List<String> extra = new ArrayList<>();
            ref.map(ReferenceRun::finishedAt).ifPresent(at -> {
                Duration age = Duration.between(at, OffsetDateTime.now());
                if (age.compareTo(props.maxReferenceAge()) > 0) {
                    extra.add("기준 기록이 오래됨 (" + age.toDays() + "일 전, 기준 " + props.maxReferenceAge().toDays() + "일)");
                }
            });
            nonJavaTestSources(checkout.headDir()).ifPresent(dir ->
                    extra.add("Java 가 아닌 테스트 소스가 있어 테스트 목록을 확정할 수 없음: " + dir));

            Trace trace = ref.map(r -> Trace.fromJson(r.trace(), mapper)).orElse(null);
            TestSelection.Reference meta = ref.map(r -> new TestSelection.Reference(r.runId(), r.prNumber(), r.sha(),
                    r.sha().equals(checkout.baseSha()), r.finishedAt() == null ? null : r.finishedAt().toString(),
                    r.tests() == null ? 0 : r.tests())).orElse(null);
            TestSelection selection = TestSelector.select(new TestSelector.Input(ctx.files(), ctx.changedMethods(),
                    ctx.baseIndex(), ctx.headIndex(), trace, meta, ref.map(r -> secondsByClass(r.results())).orElse(Map.of()),
                    extra, props.staticDepth(), props.mode() == TestSelectionProperties.Mode.SHADOW));
            sink.done(Stage.EXEC_SELECT, message(selection), view(selection, System.currentTimeMillis() - started));
            return selection;
        } catch (RuntimeException e) {
            log.warn("테스트 선택 실패: {}", e.toString());
            sink.failed(Stage.EXEC_SELECT, "선택 실패 — 전체 실행: " + e.getMessage());
            return TestSelection.off("선택 실패: " + e.getMessage());
        }
    }

    static String message(TestSelection s) {
        if (TestSelection.FULL.equals(s.mode())) {
            return "전체 실행 · " + s.fullReasons().get(0);
        }
        if (s.shadow()) {
            return "그림자 모드: " + s.total() + "개 중 " + s.impacted() + "개면 충분 (전체 실행)";
        }
        return "테스트 클래스 " + s.total() + "개 중 " + s.run() + "개 실행 · " + (s.total() - s.run()) + "개 걸러짐";
    }

    /** 단계 기록용. 화면 노드 · 상세가 쓰고, 테스트 목록은 상한까지만 싣는다 */
    private static Map<String, Object> view(TestSelection s, long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode());
        m.put("shadow", s.shadow());
        m.put("fullReasons", s.fullReasons());
        m.put("reference", s.reference());
        m.put("total", s.total());
        m.put("impacted", s.impacted());
        m.put("run", s.run());
        m.put("referenceSeconds", s.referenceSeconds());
        m.put("skippedSeconds", s.skippedSeconds());
        m.put("changes", s.changes());
        m.put("tests", s.tests().stream().limit(MAX_EVENT_TESTS).toList());
        m.put("ms", ms);
        return m;
    }

    /** 기준 실행 결과에서 최상위 테스트 클래스별 걸린 시간 */
    private Map<String, Double> secondsByClass(String resultsJson) {
        if (resultsJson == null) {
            return Map.of();
        }
        try {
            List<TestCase> cases = mapper.readValue(resultsJson, new TypeReference<List<TestCase>>() {});
            Map<String, Double> result = new HashMap<>();
            for (TestCase c : cases) {
                result.merge(TestSelector.topLevelOfTrace(c.name()), c.time(), Double::sum);
            }
            return result;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    /** 인덱스는 Java 만 읽는다. Kotlin · Groovy · Scala 테스트가 있으면 그 테스트가 무엇을 지나가는지 모른다 */
    static Optional<String> nonJavaTestSources(Path root) {
        try (Stream<Path> walk = Files.walk(root, 6)) {
            return walk.filter(Files::isDirectory)
                    .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains(".git"))
                    .filter(p -> p.endsWith(Path.of("src", "test", "kotlin")) || p.endsWith(Path.of("src", "test", "groovy"))
                            || p.endsWith(Path.of("src", "test", "scala")))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .findFirst();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
