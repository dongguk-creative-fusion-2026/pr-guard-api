package com.prguard.history;

import com.prguard.workspace.Checkout;
import com.prguard.workspace.GitRunner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** clone 해 둔 레포에서 커밋 이력과 blame 을 읽는다. */
@Component
public class GitHistoryLoader {

    private static final char RECORD = 0x1e;
    private static final char FIELD = 0x1f;
    /** blame 은 라인 범위마다 git 을 한 번씩 부르므로 PR 당 범위 수를 제한한다. */
    private static final int MAX_BLAME_RANGES = 30;

    private final GitRunner git;

    public GitHistoryLoader(GitRunner git) {
        this.git = git;
    }

    /** base 커밋까지의 최근 커밋 (merge 제외). */
    public GitHistory load(Checkout checkout, int maxCommits) {
        String out = git.run(checkout.mirror(), "log", "--no-merges", "--no-renames", "-n", String.valueOf(maxCommits),
                "--format=%x1e%H%x1f%at%x1f%an%x1f%s", "--name-only",
                checkout.baseSha());
        List<CommitInfo> commits = new ArrayList<>();
        for (String record : out.split(String.valueOf(RECORD))) {
            if (record.isBlank()) {
                continue;
            }
            String[] lines = record.split("\n");
            String[] header = lines[0].split(String.valueOf(FIELD), -1);
            if (header.length < 4) {
                continue;
            }
            List<String> files = Arrays.stream(lines).skip(1).map(String::strip).filter(s -> !s.isEmpty()).toList();
            commits.add(new CommitInfo(header[0], Long.parseLong(header[1]), header[2], header[3], files));
        }
        return new GitHistory(commits);
    }

    /** base 의 해당 라인들을 마지막으로 바꾼 커밋. 연속된 라인은 한 번에 묻는다. */
    public List<BlameLine> blame(Checkout checkout, String file, List<Integer> baseLines) {
        List<BlameLine> result = new ArrayList<>();
        for (int[] range : ranges(baseLines)) {
            String out = git.run(checkout.mirror(), "blame", "--porcelain", "-L", range[0] + "," + range[1],
                    checkout.baseSha(), "--", file);
            result.addAll(parsePorcelain(file, out));
        }
        return result;
    }

    static List<int[]> ranges(List<Integer> lines) {
        List<Integer> sorted = lines.stream().distinct().sorted().toList();
        List<int[]> ranges = new ArrayList<>();
        for (int line : sorted) {
            if (!ranges.isEmpty() && ranges.get(ranges.size() - 1)[1] == line - 1) {
                ranges.get(ranges.size() - 1)[1] = line;
            } else {
                ranges.add(new int[] {line, line});
            }
            if (ranges.size() > MAX_BLAME_RANGES) {
                ranges.remove(ranges.size() - 1);
                break;
            }
        }
        return ranges;
    }

    /** {@code git blame --porcelain} 출력. 커밋 정보는 처음 나올 때만 전체가 나온다. */
    static List<BlameLine> parsePorcelain(String file, String out) {
        List<BlameLine> result = new ArrayList<>();
        Map<String, String[]> commitInfo = new HashMap<>();
        String sha = null;
        int finalLine = 0;
        String author = "";
        long time = 0;
        String summary = "";
        for (String line : out.split("\n")) {
            if (line.matches("^[0-9a-f]{40} \\d+ \\d+.*")) {
                String[] parts = line.split(" ");
                sha = parts[0];
                finalLine = Integer.parseInt(parts[2]);
                String[] known = commitInfo.get(sha);
                if (known != null) {
                    author = known[0];
                    time = Long.parseLong(known[1]);
                    summary = known[2];
                }
            } else if (line.startsWith("author ")) {
                author = line.substring(7);
            } else if (line.startsWith("author-time ")) {
                time = Long.parseLong(line.substring(12).strip());
            } else if (line.startsWith("summary ")) {
                summary = line.substring(8);
            } else if (line.startsWith("\t") && sha != null) {
                commitInfo.putIfAbsent(sha, new String[] {author, String.valueOf(time), summary});
                result.add(new BlameLine(file, finalLine, sha, author, time, summary));
            }
        }
        return result;
    }
}
