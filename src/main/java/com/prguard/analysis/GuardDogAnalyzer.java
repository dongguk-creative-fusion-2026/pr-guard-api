package com.prguard.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.GuardDogReport.*;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Supply-chain findings and scan coverage for newly introduced direct dependencies. */
@Component
public class GuardDogAnalyzer implements Analyzer {
    private static final int MAX_REPORT_ITEMS = 500;
    private final GuardDogProperties props;
    private final GuardDogClient client;
    private final ObjectMapper mapper;

    public GuardDogAnalyzer(GuardDogProperties props, GuardDogClient client, ObjectMapper mapper) {
        this.props = props;
        this.client = client;
        this.mapper = mapper;
    }

    @Override public String id() { return "guarddog"; }
    @Override public Category category() { return Category.SECURITY; }

    private static final class Target {
        final String ecosystem;
        final String name;
        final String version;
        final Set<String> files = new LinkedHashSet<>();

        Target(String ecosystem, String name, String version) {
            this.ecosystem = ecosystem;
            this.name = name;
            this.version = version;
        }
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        long started = System.nanoTime();
        if (!props.enabled()) {
            return finish(ctx, GuardDogReport.empty(Status.DISABLED, List.of(), 0), List.of());
        }
        // An unexpected analyzer exception must not silently turn an enabled check into a clean scan.
        ctx.setGuarddog(GuardDogReport.empty(Status.FAILED,
                List.of(problem(Reason.EXECUTION_FAILED, "의존성 검사를 완료하지 못했습니다.", null)), 0));
        Map<String, ChangedFile> manifests = new LinkedHashMap<>();
        for (ChangedFile file : ctx.files()) {
            if (!DependencyManifest.supported(file.filename())
                    || (file.removed() && !file.filename().endsWith("package-lock.json"))) continue;
            manifests.merge(DependencyManifest.manifestPath(file.filename()), file,
                    (oldFile, newFile) -> newFile.filename().endsWith("package.json") ? newFile : oldFile);
        }
        if (manifests.isEmpty()) {
            return finish(ctx, GuardDogReport.empty(Status.NO_CHANGES, List.of(), elapsed(started)), List.of());
        }
        if (ctx.checkout().isEmpty()) {
            return finish(ctx, GuardDogReport.empty(Status.FAILED,
                    List.of(problem(Reason.SOURCE_UNAVAILABLE, "소스가 없어 의존성을 확인하지 못했습니다.", null)),
                    elapsed(started)), List.of());
        }

        var checkout = ctx.checkout().orElseThrow();
        Map<String, Target> targets = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        discovery:
        for (var entry : manifests.entrySet()) {
            String path = entry.getKey();
            if (Thread.currentThread().isInterrupted()) {
                problems.add(problem(Reason.INTERRUPTED, "실행 중단으로 의존성 목록을 확인하지 못했습니다.", path));
                break;
            }
            if (remaining(started).isNegative() || remaining(started).isZero()) {
                problems.add(problem(Reason.TOTAL_TIMEOUT, "검사 시간 한도 초과로 의존성 목록 수집이 중단됐습니다.", path));
                break;
            }
            if (problems.size() >= MAX_REPORT_ITEMS) {
                problems.add(problem(Reason.DISCOVERY_LIMIT, "결과 한도 초과로 남은 의존성 파일을 확인하지 못했습니다.", path));
                break;
            }
            try {
                ChangedFile file = entry.getValue();
                var head = DependencyManifest.read(checkout.headDir(), path, props.includeDevDependencies(), mapper);
                if (!head.exists() && !file.removed()) {
                    problems.add(problem(Reason.MANIFEST_ERROR, "변경된 의존성 파일을 소스에서 읽지 못했습니다.", path));
                    continue;
                }
                String previous = file.previousFilename() == null ? path
                        : DependencyManifest.manifestPath(file.previousFilename());
                var base = DependencyManifest.read(checkout.baseDir(), previous, props.includeDevDependencies(), mapper);
                for (var dependency : head.dependencies().values()) {
                    if (base.dependencies().containsKey(dependency.key())) continue;
                    if (!add(targets, dependency.key(), dependency.ecosystem(), dependency.name(), dependency.version(), path)) {
                        problems.add(problem(Reason.DISCOVERY_LIMIT, "결과 한도 초과로 남은 의존성을 수집하지 못했습니다.", path));
                        break discovery;
                    }
                }
                Set<String> existing = base.unresolved().stream().map(DependencyManifest.Unresolved::identity)
                        .collect(Collectors.toSet());
                for (var unresolved : head.unresolved()) {
                    if (existing.contains(unresolved.identity())) continue;
                    if (!add(targets, unresolved.identity(), unresolved.ecosystem(), unresolved.name(), null, path)) {
                        problems.add(problem(Reason.DISCOVERY_LIMIT, "결과 한도 초과로 남은 의존성을 수집하지 못했습니다.", path));
                        break discovery;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Parsing errors can include raw manifest values. Return a stable reason, never that raw text.
                problems.add(problem(Reason.MANIFEST_ERROR, "기준 또는 변경 후 의존성 파일을 비교하지 못했습니다.", path));
            }
        }

        List<PackageResult> packages = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int attempted = 0;
        for (Target target : targets.values()) {
            PackageResult result;
            if (target.version == null) {
                result = unavailable(target, PackageStatus.SKIPPED, Reason.UNSUPPORTED_SPEC,
                        "지원하지 않는 선언 또는 고정 버전이 없어 검사하지 못했습니다.");
            } else if (Thread.currentThread().isInterrupted()) {
                result = unavailable(target, PackageStatus.SKIPPED, Reason.INTERRUPTED, "실행 중단으로 검사하지 못했습니다.");
            } else if (attempted >= props.maxPackages()) {
                result = unavailable(target, PackageStatus.SKIPPED, Reason.PACKAGE_LIMIT, "패키지 수 한도 초과로 검사하지 못했습니다.");
            } else {
                Duration remaining = remaining(started);
                if (remaining.isNegative() || remaining.isZero()) {
                    result = unavailable(target, PackageStatus.SKIPPED, Reason.TOTAL_TIMEOUT, "전체 시간 한도 초과로 검사하지 못했습니다.");
                } else {
                    attempted++;
                    try {
                        result = parse(target, client.scan(target.ecosystem, target.name, target.version,
                                remaining.compareTo(props.timeout()) < 0 ? remaining : props.timeout()));
                    } catch (GuardDogException e) {
                        result = unavailable(target, PackageStatus.FAILED, e.reason(), e.getMessage());
                    } catch (RuntimeException e) {
                        result = unavailable(target, PackageStatus.FAILED, Reason.EXECUTION_FAILED, "GuardDog 실행에 실패했습니다.");
                    }
                }
            }
            packages.add(result);
            if (!result.risks().isEmpty()) {
                Severity severity = result.risks().stream().anyMatch(r -> r.severity().equals("high"))
                        ? Severity.MAJOR : Severity.MINOR;
                String evidence = result.risks().stream().limit(20).map(r -> r.rule() + ": " + r.description())
                        .collect(Collectors.joining("\n"));
                for (String file : result.files()) {
                    findings.add(new Finding("GUARDDOG_DEPENDENCY_RISK", Category.SECURITY, severity, file, null,
                            "의존성 위험 신호: " + target.name + "@" + target.version,
                            "GuardDog가 새로 추가되거나 버전이 바뀐 패키지에서 위험 신호를 탐지했습니다. 악성 여부를 검토하세요.",
                            truncate(evidence, 8000), target.ecosystem + ":" + target.name + "@" + target.version, Finding.TOOL));
                }
            }
        }
        return finish(ctx, GuardDogReport.completed(packages, problems, elapsed(started)), findings);
    }

    private PackageResult parse(Target target, JsonNode result) {
        if (result == null || !result.isObject() || !result.path("issues").isIntegralNumber()
                || result.path("issues").asLong() < 0 || !result.path("errors").isObject()) {
            return unavailable(target, PackageStatus.FAILED, Reason.MALFORMED_OUTPUT, "GuardDog JSON 형식이 올바르지 않습니다.");
        }
        List<Problem> problems = new ArrayList<>();
        if (!result.path("errors").isEmpty()) {
            boolean download = result.path("errors").has("download-package");
            problems.add(problem(download ? Reason.DOWNLOAD_FAILED : Reason.SCANNER_ERRORS,
                    download ? "패키지 다운로드에 실패했습니다. 패키지·버전과 네트워크를 확인하세요."
                            : "일부 GuardDog 규칙 검사에 실패했습니다.", null));
        }
        JsonNode risks = result.path("risks");
        if (!risks.isArray()) {
            if (problems.isEmpty()) problems.add(problem(Reason.MALFORMED_OUTPUT, "GuardDog v3 risks 결과가 없습니다.", null));
            return result(target, PackageStatus.FAILED, List.of(), problems);
        }
        if (result.has("package_version") && !target.version.equals(result.path("package_version").asText())) {
            return unavailable(target, PackageStatus.FAILED, Reason.MALFORMED_OUTPUT, "요청한 버전과 검사된 버전이 다릅니다.");
        }
        List<Risk> parsed = new ArrayList<>();
        boolean malformed = false;
        for (JsonNode risk : risks) {
            String severity = risk.path("severity").asText().toLowerCase(Locale.ROOT);
            if (!risk.isObject() || !List.of("low", "medium", "high").contains(severity)
                    || !risk.path("threat_rule").isTextual() || risk.path("threat_rule").asText().isBlank()) {
                malformed = true;
                continue;
            }
            parsed.add(new Risk(truncate(risk.path("threat_rule").asText(), 200), severity,
                    truncate(risk.path("threat_description").asText(risk.path("name").asText()), 2000)));
        }
        if (malformed) problems.add(problem(Reason.MALFORMED_OUTPUT, "일부 위험 근거의 형식이 올바르지 않습니다.", null));
        PackageStatus status = malformed && parsed.isEmpty() ? PackageStatus.FAILED
                : problems.isEmpty() ? PackageStatus.COMPLETED : PackageStatus.PARTIAL;
        return result(target, status, parsed, problems);
    }

    private static boolean add(Map<String, Target> targets, String key, String ecosystem, String name, String version, String file) {
        Target target = targets.get(key);
        if (target == null) {
            if (targets.size() >= MAX_REPORT_ITEMS) return false;
            target = new Target(ecosystem, name, version);
            targets.put(key, target);
        }
        target.files.add(file);
        return true;
    }

    private static PackageResult unavailable(Target target, PackageStatus status, Reason reason, String message) {
        return result(target, status, List.of(), List.of(problem(reason, message, null)));
    }

    private static PackageResult result(Target target, PackageStatus status, List<Risk> risks, List<Problem> problems) {
        return new PackageResult(target.ecosystem, target.name, target.version, List.copyOf(target.files), status, risks, problems);
    }

    private static Problem problem(Reason code, String message, String file) { return new Problem(code, message, file); }
    private Duration remaining(long started) { return props.totalTimeout().minusNanos(System.nanoTime() - started); }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static List<Finding> finish(AnalysisContext ctx, GuardDogReport report, List<Finding> findings) {
        ctx.setGuarddog(report);
        ctx.note("GuardDog: " + report.status().label() + " · 완료 " + report.counts().completed()
                + " / 일부 실패 " + report.counts().partial() + " / 실패 " + report.counts().failed()
                + " / 건너뜀 " + report.counts().skipped());
        List<String> details = new ArrayList<>();
        report.problems().forEach(p -> details.add((p.file() == null ? "" : p.file() + ": ") + p.message()));
        report.packages().stream().filter(p -> !p.problems().isEmpty()).forEach(p ->
                details.add((p.name() == null ? p.files().getFirst() : p.name() + (p.version() == null ? "" : "@" + p.version()))
                        + ": " + p.problems().getFirst().message()));
        details.stream().limit(10).forEach(detail -> ctx.note("GuardDog: " + detail));
        return findings;
    }

    private static String truncate(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }
}

