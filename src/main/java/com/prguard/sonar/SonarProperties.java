package com.prguard.sonar;

import com.prguard.analysis.Severity;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SonarCloud 분석. 등록된 레포마다 우리 조직 아래에 프로젝트를 만들고, head 러너가 테스트 뒤에 스캔해 올린다.
 *
 * @param enabled       켜도 token · organization 이 없으면 돌지 않는다
 * @param url           SonarCloud 주소
 * @param organization  프로젝트를 만들 조직 키
 * @param token         조직에 프로젝트를 만들고 분석을 올릴 수 있는 토큰
 * @param projectPrefix 프로젝트 키 앞에 붙인다 (prguard_{owner}_{repo})
 * @param timeout       러너가 올린 분석을 SonarCloud 가 처리하기를 기다리는 시간
 * @param gateSeverity  Quality Gate 실패를 어떤 심각도로 남길지
 * @param maxIssues     지적으로 남길 이슈 수 상한
 */
@ConfigurationProperties("prguard.sonar")
public record SonarProperties(Boolean enabled, String url, String organization, String token, String projectPrefix,
                              Duration timeout, Severity gateSeverity, int maxIssues) {

    public SonarProperties {
        enabled = enabled != null && enabled;
        url = url == null || url.isBlank() ? "https://sonarcloud.io" : trimSlashes(url);
        projectPrefix = projectPrefix == null ? "prguard_" : projectPrefix;
        timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        gateSeverity = gateSeverity == null ? Severity.MINOR : gateSeverity;
        maxIssues = maxIssues <= 0 ? 50 : maxIssues;
    }

    private static String trimSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    public boolean active() {
        return enabled && token != null && !token.isBlank() && organization != null && !organization.isBlank();
    }
}
