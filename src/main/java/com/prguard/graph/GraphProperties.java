package com.prguard.graph;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param node        node 실행 파일
 * @param script      GitNexus 를 돌려 그래프 JSON 을 쓰는 스크립트 (gitnexus/export.mjs)
 * @param timeout     그래프 하나를 만드는 제한 시간
 * @param maxNodes    화면에 넘길 최대 파일 수. 넘으면 연결이 많은 파일부터 남긴다
 * @param heapMb      GitNexus 분석 프로세스의 힙 (MB)
 * @param workerDelay 대기열 확인 주기
 * @param staleAfter  RUNNING 인 채로 이 시간이 지나면 죽은 작업으로 보고 다시 집는다
 */
@ConfigurationProperties("prguard.graph")
public record GraphProperties(String node, Path script, Duration timeout, int maxNodes, int heapMb,
                              Duration workerDelay, Duration staleAfter) {
}
