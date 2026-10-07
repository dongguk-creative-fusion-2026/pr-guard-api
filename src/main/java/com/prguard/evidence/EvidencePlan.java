package com.prguard.evidence;

import java.util.List;

/**
 * 실행 검증에 넘기는 분석 결과. 러너는 소스가 준비되자마자 뜨고, 이건 인덱스 · 생성이 끝난 뒤에 채워진다.
 *
 * @param tests   러너가 레포 테스트와 함께 돌릴 증거 테스트
 * @param changed 바뀐 메서드 (테스트 아닌 것). 호출 기록에서 어떤 테스트가 지나갔는지 찾는 데 쓴다
 */
public record EvidencePlan(List<EvidenceTest> tests, List<Target> changed) {

    public static final EvidencePlan EMPTY = new EvidencePlan(List.of(), List.of());

    /**
     * @param id     인덱스 id (예: com.a.Foo#bar(String))
     * @param traced 호출 기록의 이름 (예: com.a.Foo#bar). 생성자는 com.a.Foo#&lt;init&gt;
     */
    public record Target(String id, String traced, String kind, String file, int line) {
    }
}
