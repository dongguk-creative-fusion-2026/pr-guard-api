package com.prguard.review;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;

/** 시스템 프롬프트(리뷰 기준)는 resources/prompts/review-system.md 에서 고친다. */
public class ReviewPrompt {

    private final String system;
    private final int maxPatchChars;

    public ReviewPrompt(int maxPatchChars) {
        this.maxPatchChars = maxPatchChars;
        try {
            this.system = new ClassPathResource("prompts/review-system.md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String system() {
        return system;
    }

    /** PR 정보, 구조 정보, 파일별 patch. 총 길이가 maxPatchChars 를 넘으면 뒤 파일부터 patch 를 생략한다. */
    public String user(ReviewInput in) {
        StringBuilder sb = new StringBuilder();
        sb.append("레포: ").append(in.repo()).append('\n');
        sb.append("PR #").append(in.number()).append(": ").append(in.title()).append('\n');
        sb.append("작성자: ").append(in.author()).append('\n');
        sb.append("브랜치: ").append(in.headRef()).append(" -> ").append(in.baseRef()).append('\n');
        if (in.body() != null && !in.body().isBlank()) {
            sb.append("\n## PR 본문\n").append(in.body().strip()).append('\n');
        }
        if (!in.commitMessages().isEmpty()) {
            sb.append("\n## 커밋 메시지\n");
            in.commitMessages().forEach(m -> sb.append("- ").append(m.strip().replace("\n", " / ")).append('\n'));
        }
        if (in.structure() != null && !in.structure().isBlank()) {
            sb.append("\n## 레포 구조 정보 (정적 분석)\n").append(in.structure().strip()).append('\n');
        }
        sb.append("\n## 변경 파일 ").append(in.files().size()).append("개\n");

        int budget = maxPatchChars;
        for (ReviewInput.ChangedFile f : in.files()) {
            sb.append("\n--- ").append(f.filename())
                    .append(" (").append(f.status()).append(", +").append(f.additions())
                    .append(" -").append(f.deletions()).append(")\n");
            if (f.patch() == null) {
                sb.append("(patch 없음: 바이너리 또는 너무 큰 파일)\n");
            } else if (f.patch().length() > budget) {
                sb.append("(patch 생략: 입력 길이 제한)\n");
            } else {
                sb.append(f.patch()).append('\n');
                budget -= f.patch().length();
            }
        }
        return sb.toString();
    }
}
