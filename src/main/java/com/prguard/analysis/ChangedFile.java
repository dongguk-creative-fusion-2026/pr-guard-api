package com.prguard.analysis;

import com.prguard.diff.Patch;

/**
 * PR 에서 바뀐 파일 하나.
 *
 * @param patchText GitHub 이 준 원문 patch (바이너리·대용량이면 null)
 */
public record ChangedFile(
        String filename,
        String status,
        int additions,
        int deletions,
        String patchText,
        String previousFilename,
        Patch patch) {

    public boolean isJava() {
        return filename.endsWith(".java");
    }

    public boolean removed() {
        return "removed".equals(status);
    }
}
