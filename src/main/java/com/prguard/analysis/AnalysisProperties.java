package com.prguard.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxCommits     이력 통계에 쓰는 최근 커밋 수
 * @param minCoSupport   동시 변경으로 볼 최소 횟수
 * @param minConfidence  동시 변경으로 볼 최소 비율
 * @param maxStructureChars LLM 에 넘기는 구조 정보 최대 길이
 */
@ConfigurationProperties("prguard.analysis")
public record AnalysisProperties(int maxCommits, int minCoSupport, double minConfidence, int maxStructureChars) {
}
