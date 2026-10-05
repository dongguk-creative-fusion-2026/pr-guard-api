package com.prguard.index;

import java.util.List;

/**
 * 메서드 안의 호출 하나.
 *
 * @param scopeType 호출 대상 객체의 타입. 레포 안 타입이면 FQN, 알 수 없으면 null
 * @param calleeIds 이름·인자 수가 맞는 레포 안 메서드 (오버로드면 여러 개)
 */
public record CallSite(
        String callerId,
        String calleeName,
        int argCount,
        String scopeType,
        List<String> calleeIds,
        Resolution resolution,
        String file,
        int line) {

    public enum Resolution {
        /** 레포 안 메서드로 연결됨 */
        RESOLVED,
        /** 대상 객체 타입을 모름 (메서드 체이닝, 람다 파라미터 등) */
        UNKNOWN_SCOPE,
        /** 레포 밖 타입(라이브러리)의 메서드 */
        EXTERNAL,
        /** 레포 안 타입이고 상속 계층도 레포 안에 있는데 그런 이름의 메서드가 없음 */
        MISSING
    }
}
