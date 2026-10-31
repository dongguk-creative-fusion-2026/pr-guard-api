package com.prguard.sonar;

import com.fasterxml.jackson.databind.JsonNode;
import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.PullInfo;
import com.prguard.analysis.Severity;
import com.prguard.events.EventSink;
import com.prguard.events.Stage;
import com.prguard.github.GitHubClient;
import com.prguard.github.RepoRef;
import com.prguard.graph.GraphProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SonarCloud 분석 (중복률 · 복잡도 · 커버리지 · 코드 냄새 · Quality Gate).
 * <ol>
 *   <li>리뷰 시작: 우리 조직에 레포 프로젝트를 만들고(없으면) 스캔을 맡긴다
 *     <ul>
 *       <li>실행 검증이 돌면 head 러너: 테스트 뒤 {@code /api/test-runs/{id}/sonar} 에서 설정 · 토큰을 받아
 *           JaCoCo 커버리지와 함께 PR 분석을 올리고, 보고서에 처리 작업 id 를 싣는다</li>
 *       <li>아니면 GitHub Actions(.github/workflows/sonar.yml): PR 코드를 빌드 · 실행하지 않고 소스만 스캔한다 (커버리지 없음).
 *           {@code /api/sonar/token} 에서 토큰을 한 번 받고 {@code /api/sonar/result} 로 처리 작업 id 를 보낸다</li>
 *     </ul>
 *   </li>
 *   <li>다른 분석이 끝나면: 스캔과 SonarCloud 처리를 기다렸다가 이슈 · 지표 · Quality Gate 를 받아
 *       이번 PR 이 추가한 줄의 이슈만 지적으로 남긴다</li>
 * </ol>
 * 레포 주인이 SonarCloud 에 따로 등록할 필요가 없다. PR 코멘트는 PR Guard 가 직접 단다.
 */
@Service
public class SonarService {

    private static final Logger log = LoggerFactory.getLogger(SonarService.class);
    static final List<String> METRICS = List.of("new_coverage", "new_duplicated_lines_density", "new_lines",
            "new_bugs", "new_vulnerabilities", "new_code_smells", "new_security_hotspots",
            "coverage", "duplicated_lines_density", "cognitive_complexity", "ncloc");
    private static final long POLL_MS = 3_000;

    private final SonarProperties props;
    private final SonarClient client;
    private final GitHubClient github;
    private final GraphProperties workflows;
    /** head 커밋 → 스캔 설정과 스캐너(러너 · Actions)가 보낸 결과 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public SonarService(SonarProperties props, SonarClient client, GitHubClient github, GraphProperties workflows) {
        this.props = props;
        this.client = client;
        this.github = github;
        this.workflows = workflows;
    }

    /** 누가 스캔하나 */
    public enum Via {
        /** 실행 검증의 head 러너 (빌드 · 테스트 뒤, 커버리지 포함) */
        RUNNER,
        /** GitHub Actions (소스만, 커버리지 없음) */
        ACTIONS
    }

    /**
     * head 러너가 sonar-scanner 에 넘길 값.
     *
     * @param branch PR 브랜치 (sonar.pullrequest.branch)
     * @param base   PR 의 대상 브랜치 (sonar.pullrequest.base)
     */
    public record Plan(String host, String organization, String projectKey, int pullRequest, String branch, String base) {
    }

    private static final class Pending {
        final Plan plan;
        final Via via;
        volatile boolean tokenTaken;
        volatile String task;
        volatile String error;

        Pending(Plan plan, Via via) {
            this.plan = plan;
            this.via = via;
        }

        boolean reported() {
            return task != null || error != null;
        }
    }

    public boolean active() {
        return props.active();
    }

    /**
     * 리뷰 시작 때 부른다. 프로젝트를 만들고 스캔을 맡긴다.
     * 꺼져 있거나 실패하면 단계를 건너뜀 · 실패로 알리고 false.
     *
     * @param runner 이 리뷰에서 실행 검증(head 러너)이 도나. 돌면 러너가, 아니면 GitHub Actions 가 스캔한다
     */
    public boolean register(PullInfo pull, EventSink sink, boolean runner) {
        if (!props.active()) {
            sink.skipped(Stage.EXEC_SONAR, props.enabled() ? "SONAR_TOKEN · SONAR_ORGANIZATION 미설정" : "SonarCloud 꺼짐");
            return false;
        }
        String key = projectKey(props.projectPrefix(), pull.repo());
        sink.running(Stage.EXEC_SONAR, "SonarCloud 프로젝트 준비");
        try {
            client.ensureProject(key, pull.repo().fullName());
        } catch (RuntimeException e) {
            log.warn("SonarCloud 프로젝트 준비 실패 {}: {}", key, e.getMessage());
            sink.failed(Stage.EXEC_SONAR, "SonarCloud 프로젝트를 만들지 못함: " + e.getMessage());
            return false;
        }
        Plan plan = new Plan(props.url(), props.organization(), key, pull.number(), pull.headRef(), pull.baseRef());
        if (runner) {
            pending.put(pull.headSha(), new Pending(plan, Via.RUNNER));
            sink.running(Stage.EXEC_SONAR, "head 러너의 스캔 기다리는 중 (테스트 뒤, 커버리지 포함)");
            return true;
        }
        // 결과가 dispatch 보다 먼저 올 수 없도록 먼저 넣어 둔다
        pending.put(pull.headSha(), new Pending(plan, Via.ACTIONS));
        try {
            github.dispatchWorkflow(RepoRef.parse(workflows.workflowRepo()), props.workflow(), workflows.workflowRef(), Map.of(
                    "repo", pull.repo().fullName(),
                    "pr", String.valueOf(pull.number()),
                    "head_sha", pull.headSha(),
                    "head_ref", pull.headRef() == null ? "" : pull.headRef(),
                    "base_ref", pull.baseRef() == null ? "" : pull.baseRef(),
                    "project_key", key,
                    "organization", props.organization(),
                    "host", props.url()), workflows.dispatchToken());
        } catch (RuntimeException e) {
            pending.remove(pull.headSha());
            log.warn("SonarCloud 스캔 워크플로 실행 실패 {}: {}", key, e.getMessage());
            sink.failed(Stage.EXEC_SONAR, "GitHub Actions 에 스캔을 맡기지 못함 (" + workflows.workflowRepo() + " "
                    + props.workflow() + "): " + e.getMessage());
            return false;
        }
        sink.running(Stage.EXEC_SONAR, "GitHub Actions 에서 스캔 중 (소스만, 커버리지 없음)");
        return true;
    }

    /** Actions 스캔 워크플로의 요청인지 확인할 때: 이 커밋을 기다리는 Actions 스캔이 있나 */
    public boolean waitingForActions(String headSha) {
        Pending p = pending.get(headSha);
        return p != null && p.via == Via.ACTIONS && !p.reported();
    }

    /** head 러너가 빌드 전에 받아 간다 (커버리지를 모을지 정한다). 토큰은 없다 */
    public Optional<Plan> plan(String headSha) {
        return Optional.ofNullable(pending.get(headSha)).map(p -> p.plan);
    }

    /**
     * head 러너가 테스트가 끝난 뒤 한 번만 받아 간다. PR 코드가 도는 동안에는 러너에 토큰이 없다.
     * 두 번째부터는 빈 값 (누가 먼저 가져갔으면 스캔을 하지 않는다).
     */
    public synchronized Optional<String> takeToken(String headSha) {
        Pending p = pending.get(headSha);
        if (p == null || p.tokenTaken) {
            return Optional.empty();
        }
        p.tokenTaken = true;
        return Optional.of(props.token());
    }

    /** 스캐너(head 러너 보고서 · Actions 콜백)가 보낸 결과 (처리 작업 id 또는 스캔하지 못한 이유) */
    public void reported(String headSha, String task, String error) {
        Pending p = pending.get(headSha);
        if (p != null) {
            p.task = blankToNull(task);
            p.error = blankToNull(error);
        }
    }

    /** 리뷰가 중간에 끝나 collect 를 부르지 못했을 때 설정을 치운다 */
    public void forget(String headSha) {
        pending.remove(headSha);
    }

    /** 다른 분석이 끝난 뒤 부른다. 스캔과 SonarCloud 처리를 기다려 이슈 · 지표 · Quality Gate 를 지적으로 바꾼다 */
    public List<Finding> collect(PullInfo pull, AnalysisContext ctx, EventSink sink) {
        Pending p = pending.get(pull.headSha());
        if (p == null) {
            return List.of();
        }
        if (p.via == Via.ACTIONS) {
            awaitScan(p);
        }
        pending.remove(pull.headSha());
        if (p.task == null) {
            String reason = p.error != null ? p.error
                    : p.via == Via.ACTIONS ? "GitHub Actions 스캔이 " + props.scanTimeout().toMinutes() + "분 안에 결과를 보내지 않음"
                    : "head 러너가 Sonar 분석을 올리지 않음";
            sink.failed(Stage.EXEC_SONAR, reason);
            ctx.notes().add("SonarCloud: " + reason);
            return List.of();
        }
        long t = System.currentTimeMillis();
        Plan plan = p.plan;
        try {
            sink.running(Stage.EXEC_SONAR, "SonarCloud 처리 기다리는 중");
            awaitTask(p.task);
            Map<String, Set<Integer>> added = ctx.files().stream().filter(f -> !f.removed())
                    .collect(Collectors.toMap(ChangedFile::filename, f -> f.patch().addedLines(), (a, b) -> a));
            JsonNode issues = client.issues(plan.projectKey(), plan.pullRequest());
            List<Finding> findings = new ArrayList<>(issueFindings(issues, plan, added, props.maxIssues()));
            Map<String, String> measures = measures(client.measures(plan.projectKey(), plan.pullRequest(), METRICS));
            JsonNode gate = client.qualityGate(plan.projectKey(), plan.pullRequest());
            String gateStatus = gate == null ? "NONE" : gate.path("status").asText("NONE");
            List<String> failed = failedConditions(gate);
            if ("ERROR".equals(gateStatus)) {
                findings.add(new Finding("SONAR_GATE", Category.RISK, props.gateSeverity(), null, null,
                        "SonarCloud Quality Gate 실패",
                        "이번 PR 이 SonarCloud Quality Gate 기준을 넘지 못했습니다: " + String.join(", ", failed),
                        dashboard(plan), "sonar-gate", Finding.TOOL));
            }
            String summary = "이슈 " + findings.stream().filter(f -> f.ruleId().equals("SONAR_ISSUE")).count()
                    + " · Gate " + gateStatus
                    + (measures.containsKey("new_coverage") ? " · 새 코드 커버리지 " + measures.get("new_coverage") + "%" : "");
            sink.done(Stage.EXEC_SONAR, summary, data(
                    "via", p.via.name(),
                    "projectKey", plan.projectKey(),
                    "url", dashboard(plan),
                    "gate", gateStatus,
                    "failedConditions", failed,
                    "measures", measures,
                    "issues", issues == null ? 0 : issues.path("total").asInt(issues.path("issues").size()),
                    "findings", findings.stream().limit(20)
                            .map(f -> data("severity", f.severity().name(), "title", f.title(), "file", f.file(),
                                    "line", f.line(), "category", f.category().name()))
                            .toList(),
                    "ms", System.currentTimeMillis() - t));
            return findings;
        } catch (RuntimeException e) {
            log.warn("SonarCloud 결과 수집 실패 {}#{}: {}", pull.repo().fullName(), pull.number(), e.getMessage());
            ctx.notes().add("SonarCloud 결과를 받지 못함: " + e.getMessage());
            sink.failed(Stage.EXEC_SONAR, e.getMessage());
            return List.of();
        }
    }

    /** Actions 스캔이 결과를 보낼 때까지 기다린다 (시간이 다 되면 그냥 돌아온다) */
    private void awaitScan(Pending p) {
        long deadline = System.currentTimeMillis() + props.scanTimeout().toMillis();
        while (!p.reported() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 처리 작업이 끝날 때까지 기다린다. 실패 · 시간 초과면 예외 */
    private void awaitTask(String id) {
        long deadline = System.currentTimeMillis() + props.timeout().toMillis();
        while (true) {
            JsonNode task = client.task(id);
            if (task != null) {
                String status = task.path("status").asText();
                if ("SUCCESS".equals(status)) {
                    return;
                }
                if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                    throw new SonarException("SonarCloud 처리 " + status + ": " + task.path("errorMessage").asText(""));
                }
            }
            if (System.currentTimeMillis() > deadline) {
                throw new SonarException("SonarCloud 처리 시간 초과 (" + props.timeout().toMinutes() + "분)");
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SonarException("SonarCloud 처리 기다리다 중단됨");
            }
        }
    }

    /** 이번 PR 이 추가한 줄에 걸린 이슈만 */
    static List<Finding> issueFindings(JsonNode body, Plan plan, Map<String, Set<Integer>> added, int max) {
        List<Finding> result = new ArrayList<>();
        if (body == null) {
            return result;
        }
        String prefix = plan.projectKey() + ":";
        for (JsonNode issue : body.path("issues")) {
            String component = issue.path("component").asText();
            String file = component.startsWith(prefix) ? component.substring(prefix.length()) : component;
            if (!issue.hasNonNull("line") || !added.getOrDefault(file, Set.of()).contains(issue.path("line").asInt())) {
                continue;
            }
            int line = issue.path("line").asInt();
            String type = issue.path("type").asText("CODE_SMELL");
            String severity = issue.path("severity").asText("INFO");
            String rule = issue.path("rule").asText();
            String message = issue.path("message").asText("").strip();
            result.add(new Finding("SONAR_ISSUE", category(type), severity(type, severity), file, line,
                    "Sonar: " + clip(message, 80), message + " (" + rule + ")",
                    rule + " · " + type + " · " + severity + "\n" + plan.host() + "/project/issues?id=" + plan.projectKey()
                            + "&pullRequest=" + plan.pullRequest() + "&open=" + issue.path("key").asText(),
                    "sonar:" + rule + ":" + issue.path("hash").asText(String.valueOf(line)), Finding.TOOL));
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    /** 취약점 · 버그는 한 단계 낮춰 MAJOR 까지, 코드 냄새는 MINOR 까지. Sonar 혼자서 머지를 막지는 않는다 */
    static Severity severity(String type, String sonar) {
        boolean high = "BLOCKER".equals(sonar) || "CRITICAL".equals(sonar);
        if ("VULNERABILITY".equals(type) || "BUG".equals(type)) {
            return high ? Severity.MAJOR : "MAJOR".equals(sonar) ? Severity.MINOR : Severity.INFO;
        }
        return high ? Severity.MINOR : Severity.INFO;
    }

    static Category category(String type) {
        return switch (type) {
            case "VULNERABILITY" -> Category.SECURITY;
            case "BUG" -> Category.IMPACT;
            default -> Category.RISK;
        };
    }

    /** PR 분석의 new_* 지표는 value 대신 period(s) 에 값이 있다 */
    static Map<String, String> measures(JsonNode body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null) {
            return result;
        }
        for (JsonNode m : body.path("component").path("measures")) {
            String value = m.hasNonNull("value") ? m.path("value").asText()
                    : m.path("period").hasNonNull("value") ? m.path("period").path("value").asText()
                    : m.path("periods").path(0).path("value").asText(null);
            if (value != null) {
                result.put(m.path("metric").asText(), value);
            }
        }
        return result;
    }

    static List<String> failedConditions(JsonNode gate) {
        List<String> result = new ArrayList<>();
        if (gate == null) {
            return result;
        }
        for (JsonNode c : gate.path("conditions")) {
            if ("ERROR".equals(c.path("status").asText())) {
                result.add(c.path("metricKey").asText() + " " + c.path("actualValue").asText("?")
                        + " (기준 " + c.path("comparator").asText("") + " " + c.path("errorThreshold").asText("") + ")");
            }
        }
        return result;
    }

    /** prguard_{owner}_{repo}. Sonar 키에 쓸 수 없는 문자는 _ 로 */
    static String projectKey(String prefix, RepoRef repo) {
        return (prefix + repo.owner() + "_" + repo.name()).replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private static String dashboard(Plan plan) {
        return plan.host() + "/dashboard?id=" + plan.projectKey() + "&pullRequest=" + plan.pullRequest();
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static Map<String, Object> data(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
