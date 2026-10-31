package com.prguard.rulemap;

import com.prguard.github.RepoRef;
import com.prguard.index.JavaIndexer;
import com.prguard.index.RepoIndex;
import com.prguard.workspace.Checkout;
import com.prguard.workspace.RepoWorkspace;
import com.prguard.workspace.Snapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 커밋별 규칙 지도를 꺼내거나 만든다. 같은 커밋은 한 번만 만들고 저장해 둔다 (리뷰마다 LLM 없이 다시 계산하지 않도록).
 * 저장소가 안 되면 만든 지도만 쓰고 넘어간다.
 */
@Service
public class RuleMapService {

    private static final Logger log = LoggerFactory.getLogger(RuleMapService.class);

    private final RuleMapRepository repository;
    private final RepoWorkspace workspace;
    private final JavaIndexer indexer;
    private final Map<String, ReentrantLock> building = new ConcurrentHashMap<>();

    public RuleMapService(RuleMapRepository repository, RepoWorkspace workspace, JavaIndexer indexer) {
        this.repository = repository;
        this.workspace = workspace;
        this.indexer = indexer;
    }

    /** PR 의 base 와 최신 base 브랜치 커밋의 규칙 지도. latest 는 base 와 같은 커밋이면 null */
    public record Maps(RuleMap base, RuleMap latest, String latestSha) {
    }

    /** @param baseIndex 이미 만든 base 인덱스 (다시 파싱하지 않는다) */
    public Maps load(RepoRef repo, Checkout checkout, String baseRef, RepoIndex baseIndex) {
        RuleMap base = getOrBuild(repo, checkout.baseSha(),
                () -> RuleExtractor.extract(baseIndex, checkout.baseDir()));
        String tip;
        try {
            tip = workspace.branchTip(checkout, baseRef);
        } catch (RuntimeException e) {
            log.warn("{} 기본 브랜치 끝 커밋을 모름: {}", repo.fullName(), e.getMessage());
            return new Maps(base, null, null);
        }
        if (tip.equals(checkout.baseSha())) {
            return new Maps(base, null, tip);
        }
        RuleMap latest = getOrBuild(repo, tip, () -> {
            Snapshot snapshot = workspace.checkoutCommit(repo, checkout, tip);
            try {
                return RuleExtractor.extract(indexer.index(snapshot.dir()), snapshot.dir());
            } finally {
                workspace.release(snapshot);
            }
        });
        return new Maps(base, latest, tip);
    }

    /**
     * 화면용: 기본 브랜치 끝 커밋의 규칙 지도와, 그 전에 만든 지도 대비 바뀐 규칙.
     *
     * @param previous 이 커밋 전에 가장 최근에 만든 지도. 없으면 null
     */
    public record BranchMap(String branch, RuleMap map, RuleMap previous) {
    }

    public BranchMap forBranch(RepoRef repo, long repoKb, String branch) {
        String tip = workspace.fetchBranchTip(repo, repoKb, branch);
        RuleMap map = getOrBuild(repo, tip, () -> {
            Snapshot snapshot = workspace.checkoutBranch(repo, repoKb, branch);
            try {
                return RuleExtractor.extract(indexer.index(snapshot.dir()), snapshot.dir());
            } finally {
                workspace.release(snapshot);
            }
        });
        RuleMap previous = null;
        try {
            previous = repository.findPrevious(repo.fullName(), tip).orElse(null);
        } catch (RuntimeException e) {
            log.warn("지난 규칙 지도 조회 실패 {}: {}", repo.fullName(), e.getMessage());
        }
        return new BranchMap(branch, map, previous);
    }

    private RuleMap getOrBuild(RepoRef repo, String sha, Supplier<java.util.List<Rule>> extract) {
        String key = repo.fullName() + "@" + sha;
        // 레포마다 하나씩: 같은 커밋을 두 리뷰가 동시에 만들지 않고, 뒤에 온 쪽은 저장된 것을 쓴다
        ReentrantLock lock = building.computeIfAbsent(repo.fullName(), k -> new ReentrantLock());
        lock.lock();
        try {
            Optional<RuleMap> saved = find(repo, sha);
            if (saved.isPresent()) {
                return saved.get();
            }
            RuleMap map = new RuleMap(sha, extract.get(), false);
            try {
                repository.save(repo.fullName(), map);
            } catch (RuntimeException e) {
                log.warn("규칙 지도 저장 실패 {}: {}", key, e.getMessage());
            }
            return map;
        } finally {
            lock.unlock();
        }
    }

    private Optional<RuleMap> find(RepoRef repo, String sha) {
        try {
            return repository.find(repo.fullName(), sha);
        } catch (RuntimeException e) {
            log.warn("규칙 지도 조회 실패 {}@{}: {}", repo.fullName(), sha, e.getMessage());
            return Optional.empty();
        }
    }
}
