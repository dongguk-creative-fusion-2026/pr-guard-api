package com.prguard.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 변경분 변이 테스트: PR 이 바꾼 메서드에 작은 버그를 심어(PIT) 테스트가 잡는지 본다. head 실행에서만 돈다.
 *
 * @param enabled    끄면 변이 테스트를 하지 않는다
 * @param maxClasses 변이를 심을 클래스 수 상한 (바뀐 메서드가 많은 클래스부터)
 * @param timeout    러너에서 PIT 에 주는 시간. 넘으면 그때까지의 결과 없이 건너뛴다
 */
@ConfigurationProperties("prguard.execution.mutation")
public record MutationProperties(Boolean enabled, int maxClasses, Duration timeout) {

    public MutationProperties {
        enabled = enabled == null || enabled;
        maxClasses = maxClasses <= 0 ? 5 : maxClasses;
        timeout = timeout == null ? Duration.ofMinutes(6) : timeout;
    }
}
