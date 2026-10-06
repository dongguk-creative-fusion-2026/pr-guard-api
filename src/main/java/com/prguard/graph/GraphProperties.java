package com.prguard.graph;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param runner        그래프를 어디서 만들지. ACTIONS: GitHub Actions 워크플로 (운영), LOCAL: 이 서버에서 직접 (로컬 개발)
 * @param workflowRepo  그래프 워크플로가 있는 레포 (owner/name)
 * @param workflow      워크플로 파일 이름
 * @param workflowRef   워크플로를 실행할 브랜치
 * @param dispatchToken 워크플로 실행 토큰 (workflowRepo 에 Actions 쓰기 권한). 비우면 prguard.github.token
 * @param node          LOCAL: node 실행 파일
 * @param script        LOCAL: GitNexus 를 돌려 그래프 JSON 을 쓰는 스크립트 (gitnexus/export.mjs)
 * @param timeout       LOCAL: 그래프 하나를 만드는 제한 시간
 * @param maxNodes      LOCAL: 화면에 넘길 최대 파일 수. 넘으면 연결이 많은 파일부터 남긴다
 * @param heapMb        LOCAL: GitNexus 분석 프로세스의 힙 (MB)
 * @param workerDelay   대기열 확인 주기
 * @param staleAfter    RUNNING 인 채로 이 시간이 지나면 (워크플로 결과가 안 오면) 다시 집는다
 */
@ConfigurationProperties("prguard.graph")
public record GraphProperties(Runner runner, String workflowRepo, String workflow, String workflowRef,
                              String dispatchToken, String node, Path script, Duration timeout, int maxNodes,
                              int heapMb, Duration workerDelay, Duration staleAfter) {

    public enum Runner {
        ACTIONS, LOCAL
    }
}
