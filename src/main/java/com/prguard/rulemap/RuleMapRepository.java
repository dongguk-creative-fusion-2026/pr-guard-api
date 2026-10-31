package com.prguard.rulemap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RuleMapRepository {

    private static final TypeReference<List<Rule>> RULES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RuleMapRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<RuleMap> find(String repo, String commitSha) {
        return jdbc.sql("""
                        SELECT rules::text FROM rule_maps
                         WHERE repo = :repo AND commit_sha = :sha AND version = :version
                        """)
                .param("repo", repo)
                .param("sha", commitSha)
                .param("version", RuleExtractor.VERSION)
                .query(String.class)
                .optional()
                .map(json -> new RuleMap(commitSha, read(json), true));
    }

    /** 이 커밋이 아닌, 가장 최근에 만든 지도 (화면의 "지난 지도 대비 바뀐 규칙") */
    public Optional<RuleMap> findPrevious(String repo, String exceptSha) {
        return jdbc.sql("""
                        SELECT commit_sha, rules::text AS rules FROM rule_maps
                         WHERE repo = :repo AND commit_sha <> :sha AND version = :version
                         ORDER BY created_at DESC
                         LIMIT 1
                        """)
                .param("repo", repo)
                .param("sha", exceptSha)
                .param("version", RuleExtractor.VERSION)
                .query((rs, n) -> new RuleMap(rs.getString("commit_sha"), read(rs.getString("rules")), true))
                .optional();
    }

    public void save(String repo, RuleMap map) {
        try {
            jdbc.sql("""
                            INSERT INTO rule_maps (repo, commit_sha, version, rules, rule_count)
                            VALUES (:repo, :sha, :version, CAST(:rules AS jsonb), :count)
                            ON CONFLICT (repo, commit_sha, version) DO NOTHING
                            """)
                    .param("repo", repo)
                    .param("sha", map.commitSha())
                    .param("version", RuleExtractor.VERSION)
                    .param("rules", mapper.writeValueAsString(map.rules()))
                    .param("count", map.rules().size())
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Rule> read(String json) {
        try {
            return mapper.readValue(json, RULES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 규칙 지도를 읽지 못했습니다: " + e.getOriginalMessage());
        }
    }
}
