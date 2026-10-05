package com.prguard.review;

import java.util.List;

/**
 * Reviewer 에 넘기는 입력. GitHub 응답 타입과 분리해 두어서 리뷰 엔진을 바꿔도 다른 모듈은 그대로 둔다.
 */
public record ReviewInput(
        String repo,
        int number,
        String title,
        String body,
        String author,
        String baseRef,
        String headRef,
        String headSha,
        List<ChangedFile> files) {

    /** patch 는 unified diff hunk. 바이너리나 큰 파일은 null. */
    public record ChangedFile(String filename, String status, int additions, int deletions, String patch) {
    }

    public int additions() {
        return files.stream().mapToInt(ChangedFile::additions).sum();
    }

    public int deletions() {
        return files.stream().mapToInt(ChangedFile::deletions).sum();
    }
}
