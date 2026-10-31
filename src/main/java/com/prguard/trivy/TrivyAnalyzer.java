package com.prguard.trivy;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Java 인덱스와 무관하게 의존성/설정을 검사한다. 기존 CHECK_C · 저장 · 판정 · 코멘트 경로를 재사용한다. */
@Component
public class TrivyAnalyzer implements Analyzer {
    private final TrivyProperties props;
    private final TrivyScanner scanner;

    public TrivyAnalyzer(TrivyProperties props, TrivyScanner scanner) {
        this.props = props;
        this.scanner = scanner;
    }

    @Override public String id() { return "trivy"; }
    @Override public Category category() { return Category.SECURITY; }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        if (!props.enabled()) {
            ctx.notes().add("Trivy 검사 꺼짐 (TRIVY_ENABLED=false)");
            return List.of();
        }
        var checkout = ctx.checkout();
        if (checkout.isEmpty()) {
            ctx.notes().add("Trivy: base/head 소스가 없어 검사하지 못했습니다");
            return List.of();
        }
        Path base = checkout.get().baseDir();
        Path head = checkout.get().headDir();
        TrivyScanner.Pair pair = scanner.scan(base, head);
        Map<String, String> renamed = new HashMap<>();
        ctx.files().stream().filter(f -> "renamed".equals(f.status()) && f.previousFilename() != null)
                .forEach(f -> renamed.put(f.previousFilename(), f.filename()));
        // 같은 파일에 같은 규칙 위반이 하나 더 추가된 경우도 잡도록 집합 대신 개수를 비교한다.
        Map<String, Integer> existing = new HashMap<>();
        for (Finding finding : pair.base().findings()) {
            existing.merge(key(finding, renamed.getOrDefault(finding.file(), finding.file())), 1, Integer::sum);
        }
        List<Finding> introduced = new ArrayList<>();
        for (Finding finding : pair.head().findings()) {
            String key = key(finding, finding.file());
            int count = existing.getOrDefault(key, 0);
            if (count == 0) {
                introduced.add(finding);
            } else {
                existing.put(key, count - 1);
            }
        }
        if (pair.base().targets() == 0 || pair.head().targets() == 0) {
            ctx.notes().add("Trivy: 분석 가능한 대상 수 base=" + pair.base().targets()
                    + ", head=" + pair.head().targets() + ". 대상이 없으면 취약점 없음이 아니라 검사 범위가 없는 것입니다.");
        }
        // Gradle 스크립트를 실행해 의존성을 추측하지 않는다. 지원 입력이 빠진 모듈은 명시한다.
        warnGradleCoverage(head, ctx);
        return List.copyOf(introduced);
    }

    private static String key(Finding finding, String file) {
        return finding.ruleId() + "|" + file + "|" + finding.anchor();
    }

    private static void warnGradleCoverage(Path head, AnalysisContext ctx) {
        try (var paths = Files.walk(head)) {
            long missing = paths.filter(p -> p.getFileName().toString().matches("build\\.gradle(\\.kts)?"))
                    .filter(p -> !Files.isRegularFile(p.resolveSibling("gradle.lockfile"))).count();
            if (missing > 0) {
                ctx.notes().add("Trivy: gradle.lockfile 없는 Gradle 모듈 " + missing
                        + "개. build.gradle만으로 의존성을 검사하지 않습니다. lockfile 또는 JAR 등 지원 입력이 필요합니다.");
            }
        } catch (java.io.IOException e) {
            ctx.notes().add("Trivy: Gradle 검사 범위를 확인하지 못했습니다");
        }
    }
}
