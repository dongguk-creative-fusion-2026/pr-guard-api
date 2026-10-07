package com.prguard.evidence;

import java.util.List;

/**
 * 증거 테스트를 만들 재료.
 *
 * @param targets 겨냥할 바뀐 메서드들 (많아야 몇 개)
 */
public record EvidenceRequest(String repo, int number, String title, String body, List<Target> targets) {

    /**
     * @param className    만들 테스트의 완전한 클래스 이름
     * @param testPath     만들 테스트의 레포 안 경로
     * @param baseSource   base 쪽 메서드 소스
     * @param headSource   head 쪽 메서드 소스
     * @param headType     head 쪽 클래스 전체 (길면 앞부분만)
     * @param existingTest 같은 클래스를 다루는 기존 테스트 (스타일 · 준비 코드 참고용, 없으면 null)
     * @param testLibraries 테스트 클래스패스에 있다고 확인한 라이브러리 (junit-jupiter, mockito, assertj …)
     */
    public record Target(String methodId, String file, String className, String testPath, String baseSource,
                         String headSource, String headType, String existingTest, List<String> testLibraries) {
    }
}
