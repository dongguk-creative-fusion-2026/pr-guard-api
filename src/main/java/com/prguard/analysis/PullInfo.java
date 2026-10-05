package com.prguard.analysis;

import com.prguard.github.RepoRef;
import java.util.List;

/**
 * 분석 대상 PR 의 텍스트 정보. A(의도 대비 검증)의 재료.
 *
 * @param commitMessages 이 PR 의 커밋 메시지 (오래된 순)
 */
public record PullInfo(
        RepoRef repo,
        int number,
        String title,
        String body,
        String author,
        String baseRef,
        String headRef,
        String headSha,
        List<String> commitMessages) {
}
