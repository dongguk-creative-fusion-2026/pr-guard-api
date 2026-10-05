package com.prguard.workspace;

import com.prguard.github.RepoRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 레포별 clone 을 하나 유지하고, PR 마다 base/head worktree 를 만든다.
 * 같은 레포에 대한 git 작업은 한 번에 하나만 한다.
 */
@Component
public class RepoWorkspace {

    private static final Logger log = LoggerFactory.getLogger(RepoWorkspace.class);

    private final GitRunner git;
    private final WorkspaceProperties props;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RepoWorkspace(GitRunner git, WorkspaceProperties props) {
        this.git = git;
        this.props = props;
    }

    /**
     * PR 의 base(merge-base)와 head 소스를 꺼낸다. 다 쓰면 {@link #release(Checkout)} 를 불러야 한다.
     *
     * @param repoKb GitHub 이 알려 준 레포 크기. 제한을 넘으면 받지 않는다
     */
    public Checkout prepare(RepoRef repo, long repoKb, int prNumber, String baseRef, String headSha) {
        if (repoKb > props.maxRepoKb()) {
            throw new GitException("레포가 너무 큽니다 (" + repoKb + "KB > " + props.maxRepoKb() + "KB)");
        }
        ReentrantLock lock = locks.computeIfAbsent(key(repo), k -> new ReentrantLock());
        lock.lock();
        try {
            Path mirror = ensureMirror(repo);
            git.run(mirror, "fetch", "--quiet", "--no-tags", "origin",
                    "+refs/heads/" + baseRef + ":refs/remotes/origin/" + baseRef,
                    "+refs/pull/" + prNumber + "/head:refs/prguard/pr-" + prNumber);
            git.run(mirror, "cat-file", "-e", headSha + "^{commit}");
            String baseSha = git.run(mirror, "merge-base", "refs/remotes/origin/" + baseRef, headSha).strip();

            // Windows 경로 길이 제한(260자)에 걸리지 않게 짧게 짓는다
            String prefix = Integer.toHexString(key(repo).hashCode()) + "-" + prNumber + "-" + headSha.substring(0, 8);
            Path baseDir = worktree(mirror, prefix + "-base", baseSha);
            Path headDir = worktree(mirror, prefix + "-head", headSha);
            Files.setLastModifiedTime(mirror, FileTime.from(Instant.now()));
            return new Checkout(mirror, baseDir, headDir, baseSha, headSha);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    public void release(Checkout checkout) {
        for (Path dir : new Path[] {checkout.baseDir(), checkout.headDir()}) {
            try {
                git.run(checkout.mirror(), "worktree", "remove", "--force", dir.toString());
            } catch (GitException e) {
                log.warn("worktree 정리 실패 {}: {}", dir, e.getMessage());
                deleteQuietly(dir);
            }
        }
        try {
            git.run(checkout.mirror(), "worktree", "prune");
        } catch (GitException e) {
            log.debug("worktree prune 실패: {}", e.getMessage());
        }
    }

    private Path ensureMirror(RepoRef repo) throws IOException {
        Path mirror = props.dir().resolve("repos").resolve(key(repo));
        if (Files.isDirectory(mirror.resolve(".git"))) {
            return mirror;
        }
        deleteQuietly(mirror);
        Files.createDirectories(mirror.getParent());
        // blame 과 log 에 전체 이력이 필요해서 partial clone 은 쓰지 않는다
        git.run(mirror.getParent(), "clone", "--quiet", "--no-checkout", "--no-tags",
                "https://github.com/" + repo.owner() + "/" + repo.name() + ".git", mirror.getFileName().toString());
        return mirror;
    }

    private Path worktree(Path mirror, String name, String sha) throws IOException {
        Path dir = props.dir().resolve("worktrees").resolve(name);
        if (Files.exists(dir)) {
            try {
                git.run(mirror, "worktree", "remove", "--force", dir.toString());
            } catch (GitException e) {
                deleteQuietly(dir);
            }
            git.run(mirror, "worktree", "prune");
        }
        Files.createDirectories(dir.getParent());
        git.run(mirror, "worktree", "add", "--quiet", "--detach", "--force", dir.toString(), sha);
        return dir;
    }

    /** 오래 쓰지 않은 clone 을 지운다. */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public void cleanupMirrors() {
        Path repos = props.dir().resolve("repos");
        if (!Files.isDirectory(repos)) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.keepMirror());
        try (Stream<Path> dirs = Files.list(repos)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try {
                    if (Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff)) {
                        ReentrantLock lock = locks.computeIfAbsent(dir.getFileName().toString(), k -> new ReentrantLock());
                        if (lock.tryLock()) {
                            try {
                                deleteQuietly(dir);
                                log.info("오래된 clone 삭제: {}", dir.getFileName());
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                } catch (IOException e) {
                    log.debug("clone 확인 실패 {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("clone 정리 실패: {}", e.getMessage());
        }
    }

    private static String key(RepoRef repo) {
        return (repo.owner() + "__" + repo.name()).toLowerCase();
    }

    private static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    p.toFile().setWritable(true);
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 남은 파일은 다음 정리 때 지운다
                }
            });
        } catch (IOException ignored) {
            // 위와 같다
        }
    }
}
