package com.prguard.report;

import com.prguard.analysis.AnalysisResult;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.ContextSummary;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Grounding;
import com.prguard.analysis.Severity;
import java.util.List;
import org.springframework.stereotype.Component;

/** PR 에 남길 요약 코멘트와 라인 코멘트 본문. */
@Component
public class SummaryRenderer {

    /** 봇이 단 코멘트를 사람이 알아보게 하는 표식. */
    public static final String MARKER = "<!-- pr-guard:summary -->";

    private static final int MAX_FILE_ROWS = 50;
    private static final int MAX_FINDING_ROWS = 40;

    /** @param reviewUrl 화면의 리뷰 상세(분석 과정) 주소. 없으면 null */
    public String render(String headSha, List<ChangedFile> files, AnalysisResult result, String reviewUrl) {
        int additions = files.stream().mapToInt(ChangedFile::additions).sum();
        int deletions = files.stream().mapToInt(ChangedFile::deletions).sum();
        List<Finding> findings = result.findings().stream().filter(f -> !f.refuted()).toList();
        List<Finding> refuted = result.findings().stream().filter(Finding::refuted).toList();

        StringBuilder sb = new StringBuilder();
        sb.append(MARKER).append('\n');
        sb.append("## 🛡️ PR Guard 리뷰\n\n");
        sb.append("**판정: ").append(result.verdict().emoji()).append(' ').append(result.verdict().label())
                .append("** · ").append(counts(findings)).append("\n\n");
        sb.append('`').append(shortSha(headSha)).append("` 기준 · 변경 파일 ").append(files.size())
                .append("개 · +").append(additions).append(" / -").append(deletions).append("\n\n");
        if (reviewUrl != null) {
            sb.append("🔎 [분석 과정 보기](").append(reviewUrl).append(")\n\n");
        }
        if (result.summary() != null && !result.summary().isBlank()) {
            sb.append(result.summary().strip()).append("\n\n");
        }

        sb.append("### 지적 사항\n\n");
        if (findings.isEmpty()) {
            sb.append("지적 사항 없음\n\n");
        } else {
            sb.append("| 심각도 | 분류 | 근거 | 위치 | 내용 |\n|---|---|---|---|---|\n");
            findings.stream().limit(MAX_FINDING_ROWS).forEach(f -> sb.append("| ").append(badge(f.severity()))
                    .append(" | ").append(f.category().label()).append(f.source().equals(Finding.LLM) ? " · AI" : "")
                    .append(" | ").append(layers(f))
                    .append(" | ").append(location(f))
                    .append(" | **").append(cell(f.title())).append("**<br>").append(cell(f.message()))
                    .append(" |\n"));
            if (findings.size() > MAX_FINDING_ROWS) {
                sb.append("\n외 ").append(findings.size() - MAX_FINDING_ROWS).append("건\n");
            }
            sb.append('\n');
        }

        if (!refuted.isEmpty()) {
            sb.append("<details><summary>코드 인덱스와 어긋나 판정에서 뺀 AI 지적 ").append(refuted.size())
                    .append("건</summary>\n\n");
            refuted.forEach(f -> sb.append("- ~~").append(cell(f.title())).append("~~ ").append(location(f))
                    .append(" — ").append(cell(indexNote(f))).append('\n'));
            sb.append("\n</details>\n\n");
        }

        ContextSummary ctx = result.context();
        sb.append("<details><summary>분석 정보</summary>\n\n");
        if (ctx.headIndex().files() > 0) {
            long withCallers = ctx.changedMethods().stream().filter(m -> !m.callers().isEmpty()).count();
            sb.append("- 레포 인덱스: Java 파일 ").append(ctx.headIndex().files()).append("개, 메서드 ")
                    .append(ctx.headIndex().methods()).append("개\n");
            sb.append("- 바뀐 메서드 ").append(ctx.changedMethods().size()).append("개 (호출부가 있는 것 ")
                    .append(withCallers).append("개)\n");
        }
        if (ctx.historyCommits() > 0) {
            sb.append("- git 이력: 최근 커밋 ").append(ctx.historyCommits()).append("개 분석\n");
        }
        ctx.notes().forEach(n -> sb.append("- ⚠️ ").append(n).append('\n'));
        sb.append("- 판정 기준: BLOCKER 1건 이상 → 머지 비권장, MAJOR 1건 이상 → 수정 후 머지\n");
        sb.append("- 근거: 지적을 확인한 층 (인덱스 · 이력 · 실행 · AI). AI 만 주장한 지적은 \"AI 만\"\n");
        sb.append("\n</details>\n\n");

        sb.append("<details><summary>변경 파일</summary>\n\n");
        sb.append("| 파일 | 상태 | + | - |\n|---|---|---:|---:|\n");
        files.stream().limit(MAX_FILE_ROWS).forEach(f -> sb.append("| `").append(f.filename()).append("` | ")
                .append(f.status()).append(" | ").append(f.additions()).append(" | ")
                .append(f.deletions()).append(" |\n"));
        if (files.size() > MAX_FILE_ROWS) {
            sb.append("\n외 ").append(files.size() - MAX_FILE_ROWS).append("개\n");
        }
        sb.append("\n</details>\n\n");

        sb.append("<sub>pr-guard · ").append(result.reviewer()).append(" · ").append(ctx.elapsedMs()).append("ms</sub>\n");
        return sb.toString();
    }

    /** 라인 코멘트 하나의 본문. */
    public String inline(Finding f) {
        StringBuilder sb = new StringBuilder();
        sb.append(badge(f.severity())).append(" **").append(f.title()).append("**\n\n").append(f.message()).append('\n');
        if (f.evidence() != null && !f.evidence().isBlank()) {
            sb.append("\n<sub>근거: ").append(f.evidence()).append("</sub>\n");
        }
        sb.append("\n<sub>pr-guard · ").append(f.ruleId()).append("</sub>");
        return sb.toString();
    }

    /** 지적을 확인(CONFIRMED)하거나 뒷받침(RELATED)한 층 */
    private static String layers(Finding f) {
        Grounding g = f.grounding();
        if (g == null) {
            return "-";
        }
        if (g.status() == Grounding.Status.AI_ONLY) {
            return "AI 만";
        }
        StringBuilder sb = new StringBuilder();
        for (Grounding.Layer layer : g.layers()) {
            if (layer.mark() == Grounding.Mark.CONFIRMED) {
                sb.append(sb.isEmpty() ? "" : "·").append(layerLabel(layer.layer()));
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    private static String layerLabel(Grounding.LayerId id) {
        return switch (id) {
            case INDEX -> "인덱스";
            case HISTORY -> "이력";
            case RUNTIME -> "실행";
            case AI -> "AI";
        };
    }

    private static String indexNote(Finding f) {
        return f.grounding().layers().stream()
                .filter(l -> l.layer() == Grounding.LayerId.INDEX)
                .map(Grounding.Layer::note)
                .findFirst()
                .orElse("");
    }

    private static String counts(List<Finding> findings) {
        return "BLOCKER " + count(findings, Severity.BLOCKER) + " · MAJOR " + count(findings, Severity.MAJOR)
                + " · MINOR " + count(findings, Severity.MINOR);
    }

    private static long count(List<Finding> findings, Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    private static String badge(Severity severity) {
        return switch (severity) {
            case BLOCKER -> "🔴 BLOCKER";
            case MAJOR -> "🟠 MAJOR";
            case MINOR -> "🟡 MINOR";
            case INFO -> "ℹ️ INFO";
        };
    }

    private static String location(Finding f) {
        if (f.file() == null) {
            return "-";
        }
        String name = f.file().substring(f.file().lastIndexOf('/') + 1);
        return "`" + name + (f.line() != null ? ":" + f.line() : "") + "`";
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
