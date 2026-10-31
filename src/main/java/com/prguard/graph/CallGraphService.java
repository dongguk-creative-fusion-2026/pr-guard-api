package com.prguard.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * 리뷰에 쓸 프로젝트의 GitNexus 호출 그래프를 읽는다. 그래프는 기본 브랜치가 바뀌어 다시 만들 때까지 같으므로
 * 프로젝트마다 마지막으로 읽은 것을 커밋 기준으로 기억해 둔다.
 */
@Service
public class CallGraphService {

    private final RepoGraphRepository graphs;
    private final ObjectMapper mapper;
    private final Map<Long, CallGraph> cache = new ConcurrentHashMap<>();

    public CallGraphService(RepoGraphRepository graphs, ObjectMapper mapper) {
        this.graphs = graphs;
        this.mapper = mapper;
    }

    /** 아직 그래프를 만들지 못했으면 비어 있다 */
    public Optional<CallGraph> load(long projectId) {
        Optional<RepoGraphRepository.ImpactSource> source = graphs.findImpact(projectId);
        if (source.isEmpty() || source.get().json() == null) {
            return Optional.empty();
        }
        RepoGraphRepository.ImpactSource s = source.get();
        CallGraph cached = cache.get(projectId);
        if (cached != null && cached.commitSha() != null && cached.commitSha().equals(s.commitSha())) {
            return Optional.of(cached);
        }
        try {
            CallGraph graph = CallGraph.parse(s.commitSha(), s.full(), mapper.readTree(s.json()));
            cache.put(projectId, graph);
            return Optional.of(graph);
        } catch (Exception e) {
            throw new GraphException("호출 그래프를 읽지 못했습니다: " + e.getMessage());
        }
    }
}
