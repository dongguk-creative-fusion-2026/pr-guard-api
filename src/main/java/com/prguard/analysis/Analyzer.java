package com.prguard.analysis;

import java.util.List;

/**
 * 결정적 검사기 하나. 스프링 빈으로 등록하면 파이프라인이 모두 실행한다.
 * A~D 검사는 이 인터페이스를 구현해서 붙인다.
 */
public interface Analyzer {

    String id();

    List<Finding> analyze(AnalysisContext ctx);
}
