package com.prguard.diff;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 파일 하나의 unified diff.
 *
 * @param hunks 변경 블록
 */
public record Patch(List<Hunk> hunks) {

    public static final Patch EMPTY = new Patch(List.of());

    /**
     * 변경 블록 하나. 라인 번호는 1부터.
     *
     * @param addedLines   head 기준으로 추가된 라인 번호
     * @param removedLines base 기준으로 삭제된 라인 번호
     * @param contextLines head 기준으로 변경 없이 보이는 주변 라인 번호
     */
    public record Hunk(int oldStart, int oldLines, int newStart, int newLines,
                       List<Integer> addedLines, List<Integer> removedLines, List<Integer> contextLines) {
    }

    public Set<Integer> addedLines() {
        Set<Integer> lines = new TreeSet<>();
        hunks.forEach(h -> lines.addAll(h.addedLines()));
        return lines;
    }

    public Set<Integer> removedLines() {
        Set<Integer> lines = new TreeSet<>();
        hunks.forEach(h -> lines.addAll(h.removedLines()));
        return lines;
    }

    /** GitHub 이 PR 라인 코멘트(side=RIGHT)를 허용하는 라인: hunk 안의 추가·주변 라인. */
    public Set<Integer> commentableLines() {
        Set<Integer> lines = new TreeSet<>();
        hunks.forEach(h -> {
            lines.addAll(h.addedLines());
            lines.addAll(h.contextLines());
        });
        return lines;
    }
}
