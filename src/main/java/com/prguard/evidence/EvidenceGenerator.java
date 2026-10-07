package com.prguard.evidence;

import java.util.List;

/** 바뀐 메서드마다 동작 차이를 드러내는 테스트를 만든다. */
public interface EvidenceGenerator {

    /** 화면에 보이는 이름 (예: openai:gpt-5-mini, fixture) */
    String name();

    List<EvidenceTest> generate(EvidenceRequest request);
}
