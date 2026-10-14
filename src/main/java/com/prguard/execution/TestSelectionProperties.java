package com.prguard.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 영향 테스트 선택: PR 이 바꾼 코드를 지나가는 테스트만 base · head 에서 돌린다.
 *
 * @param mode            ON: 고른 테스트만 돌림, SHADOW: 전체를 돌리되 "골랐다면"을 기록해 놓친 실패가 없는지 확인, OFF: 늘 전체
 * @param maxReferenceAge 기준으로 쓸 전체 실행 기록이 이보다 오래되면 전체를 돌린다
 * @param staticDepth     호출 그래프에서 바뀐 메서드를 부르는 테스트를 찾을 때 거슬러 올라가는 단계
 */
@ConfigurationProperties("prguard.execution.selection")
public record TestSelectionProperties(Mode mode, Duration maxReferenceAge, int staticDepth) {

    public enum Mode {
        ON, SHADOW, OFF
    }

    public TestSelectionProperties {
        mode = mode == null ? Mode.ON : mode;
        maxReferenceAge = maxReferenceAge == null ? Duration.ofDays(14) : maxReferenceAge;
        staticDepth = staticDepth <= 0 ? 4 : staticDepth;
    }
}
