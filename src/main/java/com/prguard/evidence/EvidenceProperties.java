package com.prguard.evidence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 증거 테스트 (Meta ACH 방식: 바뀐 동작을 base 통과 · head 실패하는 테스트로 증명한다).
 *
 * @param enabled    끄면 레포 테스트만 돌린다
 * @param fixtureDir 있으면 LLM 대신 여기의 {owner}/{repo}/{PR 번호}/ 아래 파일을 레포 경로 그대로 쓴다 (로컬 · 시연용)
 * @param maxTargets 한 PR 에서 겨냥할 바뀐 메서드 수 상한
 */
@ConfigurationProperties("prguard.evidence")
public record EvidenceProperties(Boolean enabled, String fixtureDir, Integer maxTargets) {

    public boolean on() {
        return enabled == null || enabled;
    }

    public int targets() {
        return maxTargets == null ? 3 : maxTargets;
    }
}
