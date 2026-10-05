package com.prguard.history;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** base 시점까지의 커밋 이력과 그 통계. */
public final class GitHistory {

    /** 이보다 많은 파일을 바꾼 커밋(일괄 포맷팅, 대량 이동)은 동시 변경 통계에서 뺀다. */
    static final int MAX_FILES_PER_COMMIT = 30;

    private final List<CommitInfo> commits;
    private final Map<String, Integer> fileCommits = new HashMap<>();

    public GitHistory(List<CommitInfo> commits) {
        this.commits = List.copyOf(commits);
        for (CommitInfo c : commits) {
            for (String f : c.files()) {
                fileCommits.merge(f, 1, Integer::sum);
            }
        }
    }

    public static GitHistory empty() {
        return new GitHistory(List.of());
    }

    public List<CommitInfo> commits() {
        return commits;
    }

    /** 이 파일이 바뀐 커밋 수 (변경 빈도). */
    public int fileCommitCount(String file) {
        return fileCommits.getOrDefault(file, 0);
    }

    /**
     * file 과 함께 바뀌어 온 파일·디렉터리. support 와 confidence 기준을 넘는 것만, confidence 높은 순.
     */
    public List<CoChange> coChanges(String file, int minSupport, double minConfidence, int limit) {
        List<CommitInfo> touching = commits.stream()
                .filter(c -> c.files().size() <= MAX_FILES_PER_COMMIT && c.files().contains(file))
                .toList();
        if (touching.isEmpty()) {
            return List.of();
        }
        String ownDir = parent(file);
        Map<String, Integer> filePartners = new HashMap<>();
        Map<String, Integer> dirPartners = new HashMap<>();
        for (CommitInfo c : touching) {
            Set<String> dirsInCommit = new HashSet<>();
            for (String other : c.files()) {
                if (other.equals(file)) {
                    continue;
                }
                filePartners.merge(other, 1, Integer::sum);
                String dir = parent(other);
                if (!dir.equals(ownDir)) {
                    dirsInCommit.add(dir);
                }
            }
            dirsInCommit.forEach(d -> dirPartners.merge(d, 1, Integer::sum));
        }
        int total = touching.size();
        List<CoChange> result = new ArrayList<>();
        filePartners.forEach((partner, support) -> add(result, file, partner, false, support, total, minSupport,
                minConfidence));
        dirPartners.forEach((partner, support) -> add(result, file, partner, true, support, total, minSupport,
                minConfidence));
        result.sort(Comparator.comparingDouble(CoChange::confidence).reversed()
                .thenComparing(Comparator.comparingInt(CoChange::support).reversed()));
        return result.size() > limit ? result.subList(0, limit) : result;
    }

    private static void add(List<CoChange> out, String file, String partner, boolean directory, int support,
                            int total, int minSupport, double minConfidence) {
        double confidence = (double) support / total;
        if (support >= minSupport && confidence >= minConfidence) {
            out.add(new CoChange(file, partner, directory, support, total, Math.round(confidence * 100) / 100.0));
        }
    }

    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }
}
