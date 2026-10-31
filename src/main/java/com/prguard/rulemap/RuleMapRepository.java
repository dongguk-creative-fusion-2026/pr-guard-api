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
