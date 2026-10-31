package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import java.util.List;
import org.springframework.stereotype.Component;

/** C. 보안: Semgrep 결과 중 보안 규칙(SQL Injection · 위험한 API · 시크릿 …). 실행은 {@link SemgrepScanner} 가 한 번만 한다 */
@Component
public class SemgrepAnalyzer implements Analyzer {

    private final SemgrepScanner scanner;

    public SemgrepAnalyzer(SemgrepScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String id() {
        return "semgrep";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        return scanner.scan(ctx).stream().filter(f -> f.category() == Category.SECURITY).toList();
    }
}
