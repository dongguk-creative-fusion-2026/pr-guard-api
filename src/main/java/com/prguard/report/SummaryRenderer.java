package com.prguard.report;

import com.prguard.review.ReviewInput;
import org.springframework.stereotype.Component;

/** PR 에 남길 요약 코멘트 본문. */
@Component
public class SummaryRenderer {

    /** 봇이 단 코멘트를 사람이 알아보게 하는 표식. */
    public static final String MARKER = "<!-- pr-guard:summary -->";

    private static final int MAX_FILE_ROWS = 50;

    public String render(ReviewInput in, String reviewerName, String reviewBody) {
        StringBuilder sb = new StringBuilder();
        sb.append(MARKER).append('\n');
        sb.append("## 🛡️ PR Guard 리뷰\n\n");
        sb.append("`").append(shortSha(in.headSha())).append("` 기준 · 변경 파일 ")
                .append(in.files().size()).append("개 · +").append(in.additions())
                .append(" / -").append(in.deletions()).append("\n\n");
        sb.append(reviewBody.strip()).append("\n\n");

        sb.append("<details><summary>변경 파일</summary>\n\n");
        sb.append("| 파일 | 상태 | + | - |\n|---|---|---:|---:|\n");
        in.files().stream().limit(MAX_FILE_ROWS).forEach(f -> sb.append("| `").append(f.filename()).append("` | ")
                .append(f.status()).append(" | ").append(f.additions()).append(" | ")
                .append(f.deletions()).append(" |\n"));
        if (in.files().size() > MAX_FILE_ROWS) {
            sb.append("\n외 ").append(in.files().size() - MAX_FILE_ROWS).append("개\n");
        }
        sb.append("\n</details>\n\n");

        sb.append("<sub>pr-guard · ").append(reviewerName).append("</sub>\n");
        return sb.toString();
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
