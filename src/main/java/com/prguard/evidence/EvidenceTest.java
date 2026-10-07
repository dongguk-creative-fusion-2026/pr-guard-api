package com.prguard.evidence;

/**
 * PR 이 바꾼 동작을 실행으로 드러내려고 만든 테스트 하나.
 *
 * 두 종류가 있다.
 * <ul>
 *   <li>증거 테스트 (PrGuardEvidence…): base 에서 통과하고 head 에서 실패하도록 쓴다. 실패하면 동작 변화의 증거</li>
 *   <li>관측 테스트 (PrGuardProbe…): 판정하지 않고, 바뀐 메서드를 여러 입력으로 불러 결과를 기록만 한다.
 *       base 와 head 의 기록을 입력별로 맞대어 "동작 diff" 를 만든다</li>
 * </ul>
 *
 * @param path      레포 안에 놓을 경로 (예: src/test/java/com/a/PrGuardEvidence1Test.java)
 * @param className 완전한 클래스 이름. JUnit 보고서의 클래스 이름과 맞춰 결과를 찾는다
 * @param target    겨냥한 바뀐 메서드 (예: com.a.PostService#getPost(Long))
 * @param intent    무엇이 달라졌다고 보고 확인하는지 (사람이 읽는 한 줄)
 * @param code      테스트 소스
 */
public record EvidenceTest(String path, String className, String target, String intent, String code) {

    /** 증거 테스트 클래스 이름 앞부분 */
    public static final String PREFIX = "PrGuardEvidence";
    /** 관측 테스트 클래스 이름 앞부분 */
    public static final String PROBE_PREFIX = "PrGuardProbe";

    public boolean probe() {
        return simpleName(className).startsWith(PROBE_PREFIX);
    }

    /** PR Guard 가 만든 테스트인가. 레포 테스트와 구분해 회귀 비교에서 뺀다 */
    public static boolean isEvidence(String testName) {
        int hash = testName.indexOf('#');
        String simple = simpleName(hash < 0 ? testName : testName.substring(0, hash));
        return simple.startsWith(PREFIX) || simple.startsWith(PROBE_PREFIX);
    }

    /** 같은 자리에 놓을 관측 테스트 이름 (PrGuardEvidence1Test → PrGuardProbe1Test) */
    public static String probeName(String evidenceName) {
        return evidenceName.replace(PREFIX, PROBE_PREFIX);
    }

    private static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
