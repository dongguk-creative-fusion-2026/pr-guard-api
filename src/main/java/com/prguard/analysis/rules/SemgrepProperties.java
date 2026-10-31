package com.prguard.analysis.rules;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Semgrep 검사.
 *
 * @param enabled   끄면 검사하지 않는다. 켜져 있어도 실행 파일이 없으면 건너뛰고 이유를 남긴다
 * @param binary    semgrep 실행 파일
 * @param configs   규칙 (레지스트리 이름 p/... 또는 규칙 파일 · 디렉터리 경로). 여러 개면 모두 쓴다
 * @param bundled   PR Guard 가 들고 있는 마이그레이션 규칙(semgrep/prguard-rules.yml)도 쓴다
 * @param repoRules 레포의 .semgrep.yml · .semgrep.yaml · .semgrep/ 규칙(팀 규칙)도 쓴다. base 쪽 것을 읽는다
 * @param timeout   한 번 실행에 주는 시간
 * @param maxFiles  검사할 바뀐 파일 수 상한
 */
@ConfigurationProperties("prguard.semgrep")
public record SemgrepProperties(Boolean enabled, String binary, List<String> configs, Boolean bundled,
                                Boolean repoRules, Duration timeout, int maxFiles) {

    public SemgrepProperties {
        enabled = enabled == null || enabled;
        binary = binary == null || binary.isBlank() ? "semgrep" : binary;
        configs = configs == null || configs.isEmpty() ? List.of("p/default", "p/r2c-best-practices") : List.copyOf(configs);
        bundled = bundled == null || bundled;
        repoRules = repoRules == null || repoRules;
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        maxFiles = maxFiles <= 0 ? 200 : maxFiles;
    }
}
