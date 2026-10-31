package com.prguard.conflicts;

import com.prguard.analysis.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 기존 D 검사 → 리뷰 저장/화면/요약 코멘트 경로로 교차 PR 위험을 전달한다. */
@Component
public class ConflictAnalyzer implements Analyzer {
    private final ConflictService service;

    public ConflictAnalyzer(ConflictService service) { this.service = service; }
    @Override public String id() { return "pr-conflict-detector"; }
    @Override public Category category() { return Category.RISK; }

    @Override public List<Finding> analyze(AnalysisContext ctx) {
        var report = service.analyze(ctx.pull().repo());
        ctx.notes().addAll(report.notes());
        if (!ctx.pull().headSha().equals(report.heads().get(ctx.pull().number()))) {
            ctx.notes().add("현재 PR의 커밋과 교차 PR 분석 스냅샷이 달라 지적을 적용하지 않았습니다.");
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (var pair : report.conflicts()) {
            int self = ctx.pull().number();
            if (pair.a().number() != self && pair.b().number() != self) continue;
            var other = pair.a().number() == self ? pair.b() : pair.a();
            for (var file : pair.files()) {
                String path = ctx.files().stream().filter(f -> f.filename().equals(file.filename())
                        || file.filename().equals(f.previousFilename())).map(ChangedFile::filename)
                        .findFirst().orElse(file.filename());
                findings.add(new Finding("CROSS_PR_OVERLAP", Category.RISK, Severity.MINOR, path, null,
                        "PR #" + other.number() + "와 수정 영역 겹침",
                        "열린 PR #" + other.number() + " (" + other.title() + ")와 같은 기준 코드의 수정 영역이 겹칩니다. "
                                + "실제 머지 충돌이나 실행 오류가 확인된 것은 아닙니다. 상대 PR: " + other.url(),
                        "PR Conflict Detector · 기준 코드 줄 " + file.ranges() + " · " + report.checkedAt(),
                        "pr:" + other.number() + ":" + file.filename(), Finding.TOOL));
            }
        }
        return List.copyOf(findings);
    }
}
