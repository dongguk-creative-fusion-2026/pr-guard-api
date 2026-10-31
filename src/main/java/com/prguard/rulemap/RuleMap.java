package com.prguard.rulemap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 한 커밋의 규칙 지도: 그 커밋의 코드가 지키는 규칙 목록. 커밋마다 한 번 만들어 저장하고 다시 쓴다.
 *
 * @param commitSha 규칙을 뽑은 커밋
 * @param cached    저장해 둔 것을 꺼냈는가 (이번에 새로 만들었으면 false)
 */
public record RuleMap(String commitSha, List<Rule> rules, boolean cached) {

    public static final RuleMap EMPTY = new RuleMap(null, List.of(), false);

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public List<Rule> about(String subject) {
        return rules.stream().filter(r -> r.subject().equals(subject)).toList();
    }

    public Optional<Rule> convention(String key) {
        return rules.stream().filter(r -> r.kind() == Rule.Kind.CONVENTION && r.subject().equals(key)).findFirst();
    }

    public List<Rule> conventions() {
        return rules.stream().filter(r -> r.kind() == Rule.Kind.CONVENTION).toList();
    }

    /** 규칙 id → 규칙 */
    public Map<String, Rule> byId() {
        return rules.stream().collect(Collectors.toMap(Rule::id, r -> r, (a, b) -> a, LinkedHashMap::new));
    }

    public Set<String> ids() {
        return byId().keySet();
    }

    public RuleMap asCached() {
        return new RuleMap(commitSha, rules, true);
    }
}
