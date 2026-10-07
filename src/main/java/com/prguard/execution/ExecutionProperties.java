package com.prguard.execution;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실행 검증(PR 의 테스트를 base · head 에서 실제로 돌리기) 설정.
 *
 * @param runner       NONE: 끔(화면에 "실행 환경 준비 중"), KUBERNETES: Job, DOCKER: 이 서버의 docker (로컬 개발)
 * @param image        러너 이미지 (runner/Dockerfile)
 * @param callbackUrl  러너가 진행 · 결과를 보낼 이 API 의 주소 (러너 쪽에서 닿는 주소)
 * @param namespace    KUBERNETES: Job 을 만들 네임스페이스
 * @param runtimeClass KUBERNETES: 격리 런타임 (예: gvisor). 비우면 기본 런타임
 * @param cpu          러너 하나의 CPU 한도
 * @param memory       러너 하나의 메모리 한도
 * @param timeout      빌드 · 테스트 제한 시간 (넘으면 실패로 보고 정리한다)
 * @param pollInterval 상태 확인 주기
 */
@ConfigurationProperties("prguard.execution")
public record ExecutionProperties(
        Runner runner,
        String image,
        String callbackUrl,
        String namespace,
        String runtimeClass,
        String cpu,
        String memory,
        Duration timeout,
        Duration pollInterval) {

    public enum Runner {
        NONE, KUBERNETES, DOCKER
    }

    public boolean enabled() {
        return runner != null && runner != Runner.NONE;
    }
}
