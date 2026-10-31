package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * D. 위험도: Semgrep 결과 중 보안이 아닌 것 — 버그 패턴 · 모범 사례 · 팀 규칙 · 마이그레이션(수정 제안).
 * {@link SemgrepAnalyzer} 와 같은 실행 결과를 나눠 쓴다.
 */
@Component
public class SemgrepQualityAnalyzer implements Analyzer {

    private final SemgrepScanner scanner;

    public SemgrepQualityAnalyzer(SemgrepScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String id() {
        return "semgrep-quality";
    }

    @Override
    public Category category() {
        return Category.RISK;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        return scanner.scan(ctx).stream().filter(f -> f.category() != Category.SECURITY).toList();
    }
}
