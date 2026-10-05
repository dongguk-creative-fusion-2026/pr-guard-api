package com.prguard.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitHub 이 주는 파일별 patch(헤더 없이 @@ 부터 시작하는 unified diff)를 해석한다. */
public final class PatchParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private PatchParser() {
    }

    public static Patch parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return Patch.EMPTY;
        }
        List<Patch.Hunk> hunks = new ArrayList<>();
        Builder current = null;
        for (String line : patch.split("\n", -1)) {
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                if (current != null) {
                    hunks.add(current.build());
                }
                current = new Builder(
                        Integer.parseInt(m.group(1)), m.group(2) == null ? 1 : Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(4) == null ? 1 : Integer.parseInt(m.group(4)));
                continue;
            }
            if (current == null || line.startsWith("\\")) {
                // "\ No newline at end of file"
                continue;
            }
            if (line.startsWith("+")) {
                current.added.add(current.newLine++);
            } else if (line.startsWith("-")) {
                current.removed.add(current.oldLine++);
            } else if (current.oldLine < current.oldStart + current.oldLines
                    || current.newLine < current.newStart + current.newLines) {
                // 주변 라인 (" " 로 시작). split 마지막의 빈 문자열은 범위 밖이라 걸러진다
                current.context.add(current.newLine++);
                current.oldLine++;
            }
        }
        if (current != null) {
            hunks.add(current.build());
        }
        return new Patch(List.copyOf(hunks));
    }

    private static final class Builder {
        final int oldStart;
        final int oldLines;
        final int newStart;
        final int newLines;
        int oldLine;
        int newLine;
        final List<Integer> added = new ArrayList<>();
        final List<Integer> removed = new ArrayList<>();
        final List<Integer> context = new ArrayList<>();

        Builder(int oldStart, int oldLines, int newStart, int newLines) {
            this.oldStart = oldStart;
            this.oldLines = oldLines;
            this.newStart = newStart;
            this.newLines = newLines;
            this.oldLine = oldStart;
            this.newLine = newStart;
        }

        Patch.Hunk build() {
            return new Patch.Hunk(oldStart, oldLines, newStart, newLines,
                    List.copyOf(added), List.copyOf(removed), List.copyOf(context));
        }
    }
}
