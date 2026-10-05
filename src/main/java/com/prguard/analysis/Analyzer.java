package com.prguard.analysis;

import java.util.List;

/**
 * 결정적 검사기 하나. 스프링 빈으로 등록하면 파이프라인이 모두 실행한다.
 * A~D 검사는 이 인터페이스를 구현해서 붙인다.
 */
public interface Analyzer {

    String id();

    /** 화면의 어느 검사 노드(A~D)에 묶을지. 분류 없는 기반 검사는 GENERAL (B 노드에 묶임). */
    default Category category() {
        return Category.GENERAL;
    }

    List<Finding> analyze(AnalysisContext ctx);
}
