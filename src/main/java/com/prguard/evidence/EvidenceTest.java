package com.prguard.evidence;

/**
 * PR 이 바꾼 동작을 실행으로 드러내려고 만든 테스트 하나 (base 에서는 통과하고 head 에서는 실패하도록 쓴다).
 *
 * @param path      레포 안에 놓을 경로 (예: src/test/java/com/a/PrGuardEvidence1Test.java)
 * @param className 완전한 클래스 이름. JUnit 보고서의 클래스 이름과 맞춰 결과를 찾는다
 * @param target    겨냥한 바뀐 메서드 (예: com.a.PostService#getPost(Long))
 * @param intent    무엇이 달라졌다고 보고 확인하는지 (사람이 읽는 한 줄)
 * @param code      테스트 소스
 */
public record EvidenceTest(String path, String className, String target, String intent, String code) {

    /** 증거 테스트 클래스 이름 앞부분. 레포 테스트와 구분해 회귀 비교에서 뺀다 */
    public static final String PREFIX = "PrGuardEvidence";

    public static boolean isEvidence(String testName) {
        int hash = testName.indexOf('#');
        String type = hash < 0 ? testName : testName.substring(0, hash);
        return type.substring(type.lastIndexOf('.') + 1).startsWith(PREFIX);
    }
}
