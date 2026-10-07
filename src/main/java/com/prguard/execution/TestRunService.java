package com.prguard.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.PullInfo;
import com.prguard.analysis.Severity;
import com.prguard.evidence.EvidencePlan;
import com.prguard.evidence.EvidenceTest;
import com.prguard.evidence.ProbeHelper;
import com.prguard.events.EventSink;
import com.prguard.events.Stage;
import com.prguard.execution.JUnitReportParser.TestCase;
import com.prguard.execution.TestRunner.Phase;
import com.prguard.execution.TestRunner.RunnerStatus;
import com.prguard.execution.TestRunner.TestRunJob;
import com.prguard.workspace.Checkout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 실행 검증: PR 의 base · head 커밋에서 레포의 테스트를 실제로 돌려, base 에서 통과하던 테스트가 head 에서 깨지면 지적한다.
 *
 * 화면의 실행 검증 레인 단계마다 진행을 알린다:
 * 실행 준비(EXEC_PREPARE) → Pod 기동(EXEC_POD_*) → 빌드 · 테스트(EXEC_TEST_*) → 차등 비교(EXEC_DIFF).
 * 증거 테스트(EXEC_EVIDENCE)는 분석 쪽에서 만들어 넘기고, 러너는 clone 뒤 그것을 받아 레포 테스트와 함께 돌린다.
 * 러너는 진행과 결과를 {@link TestRunController} 로 보내고, 여기서는 그 기록과 러너(Pod) 상태를 주기적으로 읽는다.
 */
@Service
public class TestRunService {

    private static final Logger log = LoggerFactory.getLogger(TestRunService.class);
    static final List<Stage> STAGES = List.of(Stage.EXEC_PREPARE, Stage.EXEC_POD_BASE, Stage.EXEC_POD_HEAD,
            Stage.EXEC_TEST_BASE, Stage.EXEC_TEST_HEAD, Stage.EXEC_DIFF, Stage.EXEC_EVIDENCE);
    /** 러너(Pod)가 끝났는데 결과가 안 오면 이만큼 기다린 뒤 실패로 본다 */
    private static final long REPORT_GRACE_MS = 30_000;
    private static final int MAX_LISTED = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExecutionProperties props;
    private final TestRunRepository runs;
    private final ObjectMapper mapper;
    private volatile TestRunner runner;

    public TestRunService(ExecutionProperties props, TestRunRepository runs, ObjectMapper mapper) {
        this.props = props;
        this.runs = runs;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** 쿠버네티스 접속은 처음 쓸 때 만든다 (꺼져 있거나 설정이 없어도 서버는 뜨게) */
    private TestRunner runner() {
        if (runner == null) {
            synchronized (this) {
                if (runner == null) {
                    runner = switch (props.runner()) {
                        case KUBERNETES -> new KubernetesTestRunner(props);
                        case DOCKER -> new DockerTestRunner(props);
                        case NONE -> throw new IllegalStateException("실행 검증이 꺼져 있습니다");
                    };
                }
            }
        }
        return runner;
    }

    public void skipAll(EventSink sink, String reason) {
        STAGES.forEach(stage -> sink.skipped(stage, reason));
    }

    /** base · head 에서 테스트를 돌리고 비교해 지적을 돌려준다. 끝날 때까지 기다린다. */
    public List<Finding> verify(Long reviewId, PullInfo pull, Checkout checkout, EventSink sink) {
        return verify(reviewId, pull, checkout, sink, CompletableFuture.completedFuture(EvidencePlan.EMPTY));
    }

    /** @param plan 증거 테스트와 바뀐 메서드. 러너가 뜬 뒤에 채워진다 */
    public List<Finding> verify(Long reviewId, PullInfo pull, Checkout checkout, EventSink sink,
                                CompletableFuture<EvidencePlan> plan) {
        if (!enabled()) {
            skipAll(sink, "실행 환경 준비 중");
            return List.of();
        }
        Side base = new Side("BASE", checkout.baseSha(), Stage.EXEC_POD_BASE, Stage.EXEC_TEST_BASE);
        Side head = new Side("HEAD", checkout.headSha(), Stage.EXEC_POD_HEAD, Stage.EXEC_TEST_HEAD);
        List<Side> sides = List.of(base, head);
        try {
            long prepareStarted = System.currentTimeMillis();
            sink.running(Stage.EXEC_PREPARE, props.runner() == ExecutionProperties.Runner.KUBERNETES
                    ? "base · head Job 만드는 중" : "base · head 컨테이너 만드는 중");
            String repoUrl = "https://github.com/" + pull.repo().owner() + "/" + pull.repo().name() + ".git";
            for (Side s : sides) {
                String token = token();
                s.runId = runs.create(reviewId, s.name, s.sha, sha256(token));
                String callback = trimSlash(props.callbackUrl()) + "/api/test-runs/" + s.runId;
                s.runnerName = runner().start(new TestRunJob(s.runId, s.name, repoUrl, pull.number(), s.sha, callback, token));
                runs.started(s.runId, s.runnerName);
                s.startedAt = System.currentTimeMillis();
            }
            // 러너는 clone 뒤 증거 테스트가 준비될 때까지 기다린다 (extra-files 202)
            plan.whenComplete((p, err) -> {
                List<EvidenceTest> tests = new ArrayList<>(p == null ? List.of() : p.tests());
                // 관측 테스트가 쓰는 기록기를 같은 패키지에 함께 넣는다
                tests.addAll(ProbeHelper.filesFor(tests));
                for (Side s : sides) {
                    runs.setExtra(s.runId, tests.isEmpty() ? "NONE" : "READY", tests.isEmpty() ? null : json(tests));
                }
            });
            sink.done(Stage.EXEC_PREPARE, props.runner() == ExecutionProperties.Runner.KUBERNETES
                    ? "Job 2개 (" + props.namespace() + ")" : "컨테이너 2개", data(
                    "runner", props.runner().name(),
                    "image", props.image(),
                    "base", base.runnerName,
                    "head", head.runnerName,
                    "ms", System.currentTimeMillis() - prepareStarted));
            watch(sides, sink);
            return compare(base, head, sink, planOf(plan));
        } catch (RuntimeException e) {
            String reason = describe(e);
            log.warn("실행 검증 실패: {}", reason, e);
            sink.failed(Stage.EXEC_PREPARE, reason);
            for (Side s : sides) {
                if (s.runId > 0) {
                    runs.fail(s.runId, reason, null, null);
                }
            }
            return List.of();
        } finally {
            for (Side s : sides) {
                if (s.runnerName != null) {
                    runner().cleanup(s.runnerName);
                }
            }
        }
    }

    /** 러너(Pod) 상태와 러너가 보낸 진행을 읽어 단계별로 알린다. 둘 다 끝나거나 시간이 다 되면 돌아온다. */
    private void watch(List<Side> sides, EventSink sink) {
        long deadline = System.currentTimeMillis() + props.timeout().toMillis() + 60_000;
        while (sides.stream().anyMatch(s -> !s.finished) && System.currentTimeMillis() < deadline) {
            for (Side s : sides) {
                if (!s.finished) {
                    poll(s, sink);
                }
            }
            sleep(props.pollInterval().toMillis());
        }
        for (Side s : sides) {
            if (!s.finished) {
                String reason = "시간 초과 (" + props.timeout().toMinutes() + "분)";
                runs.fail(s.runId, reason, null, null);
                if (!s.podDone) {
                    sink.failed(s.pod, reason);
                }
                sink.failed(s.test, reason);
                s.finished = true;
            }
        }
    }

    private void poll(Side s, EventSink sink) {
        RunnerStatus status;
        try {
            status = runner().status(s.runnerName);
        } catch (RuntimeException e) {
            status = null;
        }
        // Pod 기동
        if (!s.podDone && status != null) {
            if (status.started()) {
                s.podDone = true;
                sink.done(s.pod, "기동 " + seconds(System.currentTimeMillis() - s.startedAt) + "초", data(
                        "name", s.runnerName, "ms", System.currentTimeMillis() - s.startedAt));
            } else if (status.phase() == Phase.FAILED) {
                s.podDone = true;
                s.finished = true;
                runs.fail(s.runId, status.message(), null, null);
                sink.failed(s.pod, status.message());
                sink.failed(s.test, "러너가 뜨지 못함");
                return;
            } else if (!Objects.equals(status.message(), s.podMessage)) {
                s.podMessage = status.message();
                sink.running(s.pod, status.message());
            }
        }
        // 빌드 · 테스트 (러너가 보낸 진행 · 결과)
        TestRun run = runs.find(s.runId).orElse(null);
        if (run == null) {
            return;
        }
        if ("DONE".equals(run.status())) {
            s.finished = true;
            s.result = run;
            int failed = nz(run.failures()) + nz(run.errors());
            sink.done(s.test, "테스트 " + nz(run.tests()) + " · 실패 " + failed, data(
                    "tests", nz(run.tests()), "failed", failed, "skipped", nz(run.skipped()),
                    "exitCode", run.exitCode() == null ? -1 : run.exitCode(),
                    "ms", System.currentTimeMillis() - s.startedAt));
            return;
        }
        if ("FAILED".equals(run.status())) {
            s.finished = true;
            s.result = run;
            sink.failed(s.test, run.error());
            return;
        }
        if (run.phase() != null && !Objects.equals(run.phase(), s.testPhase)) {
            s.testPhase = run.phase();
            if (!s.podDone) {
                // 러너가 진행을 보냈다면 컨테이너는 이미 떠 있다
                s.podDone = true;
                sink.done(s.pod, "기동 " + seconds(System.currentTimeMillis() - s.startedAt) + "초",
                        data("name", s.runnerName, "ms", System.currentTimeMillis() - s.startedAt));
            }
            sink.running(s.test, run.message() == null ? run.phase() : run.message());
        }
        // 러너는 끝났는데 결과가 오지 않음
        if (status != null && (status.phase() == Phase.SUCCEEDED || status.phase() == Phase.FAILED) && status.started()) {
            if (s.endedAt == 0) {
                s.endedAt = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - s.endedAt > REPORT_GRACE_MS) {
                String reason = "러너가 결과를 보내지 않고 끝남 (" + status.message() + ")";
                runs.fail(s.runId, reason, null, null);
                s.finished = true;
                s.result = runs.find(s.runId).orElse(null);
                sink.failed(s.test, reason);
            }
        }
    }

    /**
     * base 에서 통과하던 테스트가 head 에서 실패하면 BLOCKER, 새로 추가된 테스트가 실패하면 MAJOR.
     * 증거 테스트는 따로 판정한다: base 통과 · head 실패면 PR 이 그 동작을 바꿨다는 증거라 MAJOR.
     * head 호출 기록이 있으면 바뀐 메서드마다 지나간 테스트를 찾고, 하나도 없으면 MINOR.
     */
    private List<Finding> compare(Side base, Side head, EventSink sink, EvidencePlan plan) {
        if (base.result == null || !"DONE".equals(base.result.status())) {
            sink.skipped(Stage.EXEC_DIFF, "base 실행이 끝나지 않아 비교하지 않음");
            return List.of();
        }
        if (head.result == null || !"DONE".equals(head.result.status())) {
            // base 는 빌드 · 테스트가 됐는데 head 는 안 됨 = 이번 PR 이 빌드를 깨뜨렸을 가능성
            String reason = head.result == null ? "실행 실패" : head.result.error();
            sink.done(Stage.EXEC_DIFF, "head 빌드 · 테스트 실패", data("headFailed", true, "reason", reason));
            return List.of(new Finding("EXEC_HEAD_BUILD_FAILED", Category.IMPACT, Severity.BLOCKER, null, null,
                    "head 에서 빌드 · 테스트를 실행하지 못함",
                    "base 커밋에서는 빌드와 테스트가 돌았지만 이 PR 의 head 커밋에서는 실패했습니다: " + reason,
                    head.result == null ? null : head.result.logTail(), "head-build", Finding.TOOL));
        }
        List<TestCase> baseCases = cases(base.result);
        List<TestCase> headCases = cases(head.result);
        TestDiff diff = TestDiff.compare(repoTests(baseCases), repoTests(headCases));
        List<Finding> findings = new ArrayList<>();
        for (TestCase t : diff.regressions()) {
            findings.add(new Finding("EXEC_TEST_REGRESSION", Category.IMPACT, Severity.BLOCKER, null, null,
                    "base 에서 통과하던 테스트가 head 에서 실패: " + shortName(t.name()),
                    "이 PR 을 적용하면 기존 테스트 " + t.name() + " 가 실패합니다.", t.message(), t.name(), Finding.TOOL));
        }
        for (TestCase t : diff.newFailures()) {
            findings.add(new Finding("EXEC_NEW_TEST_FAILS", Category.IMPACT, Severity.MAJOR, null, null,
                    "새로 추가된 테스트가 실패: " + shortName(t.name()),
                    "이 PR 에서 생긴 테스트 " + t.name() + " 가 head 에서 실패합니다.", t.message(), t.name(), Finding.TOOL));
        }

        List<EvidenceVerdict> evidence = EvidenceVerdict.judge(plan.tests(), baseCases, headCases,
                base.result.evidenceDropped(), head.result.evidenceDropped());
        for (EvidenceVerdict v : evidence) {
            if (v.kind() == EvidenceVerdict.Kind.PROVEN) {
                EvidenceTest t = v.test();
                findings.add(new Finding("EXEC_BEHAVIOR_CHANGE", Category.IMPACT, Severity.MAJOR, file(plan, t.target()),
                        null, "실행으로 증명된 동작 변화: " + shortName(t.target()),
                        (t.intent().isBlank() ? "" : t.intent() + " — ")
                                + "base 에서 통과하는 테스트가 head 에서 실패합니다. 의도한 변경이면 호출하는 쪽과 기존 테스트도 맞춰 주세요.",
                        // 테스트 코드는 파이프라인 상세(EXEC_EVIDENCE · EXEC_DIFF)에서 보여 주고 근거에는 실패 내용만 남긴다
                        "증거 테스트 " + shortName(t.className()) + " 의 head 실패: "
                                + (v.headMessage() == null ? "" : v.headMessage()),
                        "evidence:" + t.target(), Finding.TOOL));
            }
        }

        // 동작 diff: 관측 테스트가 입력별로 남긴 결과를 base · head 로 맞댄다
        List<BehaviorDiff.Row> behavior = BehaviorDiff.compare(plan.tests(), BehaviorDiff.fromJson(runs.probe(base.runId), mapper),
                BehaviorDiff.fromJson(runs.probe(head.runId), mapper));
        Set<String> provenTargets = new HashSet<>();
        evidence.stream().filter(v -> v.kind() == EvidenceVerdict.Kind.PROVEN).forEach(v -> provenTargets.add(v.test().target()));
        for (Map.Entry<String, List<BehaviorDiff.Row>> e : BehaviorDiff.changedByTarget(behavior).entrySet()) {
            // 증거 테스트가 이미 증명한 메서드는 같은 지적을 두 번 남기지 않는다
            if (provenTargets.contains(e.getKey())) {
                continue;
            }
            findings.add(new Finding("EXEC_BEHAVIOR_DIFF", Category.IMPACT, Severity.MAJOR, file(plan, e.getKey()), null,
                    "실행으로 확인된 동작 차이: " + shortName(e.getKey()),
                    "같은 입력으로 base 와 head 를 돌렸더니 결과가 " + e.getValue().size() + "곳에서 달라졌습니다. 의도한 변경이면 PR 본문에 적고 호출하는 쪽을 확인해 주세요.",
                    String.join("\n", e.getValue().stream().limit(MAX_LISTED)
                            .map(r -> r.label() + ": " + r.base() + " → " + r.head()).toList()),
                    "behavior:" + e.getKey(), Finding.TOOL));
        }

        Trace headTrace = Trace.fromJson(runs.trace(head.runId), mapper);
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<String> untested = new ArrayList<>();
        for (EvidencePlan.Target c : plan.changed()) {
            List<String> tests = headTrace.testsReaching(c.traced()).stream()
                    .filter(name -> !EvidenceTest.isEvidence(name)).toList();
            coverage.add(data("id", c.id(), "kind", c.kind(), "file", c.file(), "calls", headTrace.callsOf(c.traced()),
                    "tests", tests.stream().limit(MAX_LISTED).toList(), "testCount", tests.size()));
            if (tests.isEmpty() && !headTrace.isEmpty()) {
                untested.add(c.id());
            }
        }
        if (!untested.isEmpty()) {
            findings.add(new Finding("EXEC_UNTESTED_CHANGE", Category.IMPACT, Severity.MINOR, null, null,
                    "테스트가 한 번도 지나가지 않은 변경 " + untested.size() + "곳",
                    "head 에서 레포 테스트를 돌리는 동안 이 메서드들은 한 번도 실행되지 않았습니다. 이 변경은 테스트로 지켜지지 않습니다.",
                    String.join("\n", untested.stream().limit(MAX_LISTED).toList()), "untested", Finding.TOOL));
        }

        long proven = evidence.stream().filter(v -> v.kind() == EvidenceVerdict.Kind.PROVEN).count();
        String summary = diff.regressions().isEmpty() && diff.newFailures().isEmpty()
                ? "회귀 없음" : "회귀 " + diff.regressions().size() + " · 새 실패 " + diff.newFailures().size();
        long differing = behavior.stream().filter(BehaviorDiff.Row::changed).count();
        if (proven > 0) {
            summary += " · 동작 변화 " + proven;
        }
        if (differing > 0) {
            summary += " · 결과 차이 " + differing;
        }
        sink.done(Stage.EXEC_DIFF, summary, data(
                "regressions", names(diff.regressions()),
                "newFailures", names(diff.newFailures()),
                "fixed", names(diff.fixed()),
                "stillFailing", diff.stillFailing().size(),
                "evidence", evidence.stream().map(EvidenceVerdict::view).toList(),
                "coverage", coverage,
                "behavior", behavior,
                "traced", !headTrace.isEmpty()));
        return findings;
    }

    private static List<TestCase> repoTests(List<TestCase> cases) {
        return cases.stream().filter(c -> !EvidenceTest.isEvidence(c.name())).toList();
    }

    private static String file(EvidencePlan plan, String methodId) {
        return plan.changed().stream().filter(c -> c.id().equals(methodId)).map(EvidencePlan.Target::file)
                .findFirst().orElse(null);
    }

    /** 러너가 끝났으면 증거 테스트는 이미 넘겨졌다. 그래도 생성이 늦으면 오래 기다리지 않는다 */
    private static EvidencePlan planOf(CompletableFuture<EvidencePlan> plan) {
        try {
            return plan.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EvidencePlan.EMPTY;
        } catch (java.util.concurrent.ExecutionException | TimeoutException e) {
            return EvidencePlan.EMPTY;
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<TestCase> cases(TestRun run) {
        if (run.results() == null) {
            return List.of();
        }
        try {
            return mapper.readValue(run.results(), new TypeReference<List<TestCase>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private static List<String> names(List<TestCase> cases) {
        return cases.stream().limit(MAX_LISTED).map(TestCase::name).toList();
    }

    private static String shortName(String name) {
        int hash = name.indexOf('#');
        String type = hash < 0 ? name : name.substring(0, hash);
        return type.substring(type.lastIndexOf('.') + 1) + (hash < 0 ? "" : name.substring(hash));
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long seconds(long ms) {
        return Math.round(ms / 1000.0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("실행 검증 중단");
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 쿠버네티스 클라이언트는 원인을 안쪽 예외에 감춘다. 바깥부터 안쪽까지 메시지를 잇는다 */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null && sb.length() < 400; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank() && sb.indexOf(m) < 0) {
                sb.append(sb.length() == 0 ? "" : " ← ").append(m.strip());
            }
        }
        return sb.length() == 0 ? e.getClass().getSimpleName() : sb.toString();
    }

    static String token() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** base 또는 head 한쪽의 진행 상태 */
    private static final class Side {
        final String name;
        final String sha;
        final Stage pod;
        final Stage test;
        long runId;
        String runnerName;
        long startedAt;
        long endedAt;
        boolean podDone;
        String podMessage;
        String testPhase;
        boolean finished;
        TestRun result;

        Side(String name, String sha, Stage pod, Stage test) {
            this.name = name;
            this.sha = sha;
            this.pod = pod;
            this.test = test;
        }
    }
}
