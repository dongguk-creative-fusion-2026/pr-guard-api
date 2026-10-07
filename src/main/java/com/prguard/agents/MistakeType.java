package com.prguard.agents;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 리뷰 지적을 묶는 실수 유형과, 그 유형이 반복되면 AGENTS.md 에 넣을 규칙.
 *
 * LLM 지적은 같은 문제라도 문장이 매번 달라 규칙 id 로는 묶이지 않는다. 그래서 규칙 id 와 제목 · 본문의
 * 핵심 낱말로 유형을 정한다. 위에서부터 처음 맞는 유형 하나로 정한다 (구체적인 유형을 위에 둔다).
 */
public enum MistakeType {

    BEHAVIOR_CHANGE("말없이 바뀐 동작",
            "기존 동작(반환값 · 예외 · 부수 효과)을 바꾸면 PR 본문에 \"동작 변화\" 를 적고, 그 동작을 확인하는 테스트를 함께 고친다.",
            Set.of("EXEC_BEHAVIOR_CHANGE"), List.of("null을 반환", "null 을 반환", "null 반환", "npe", "동작이 바뀌", "동작 변경")),
    TEST_REGRESSION("기존 테스트를 깨뜨림",
            "PR 을 올리기 전에 전체 테스트를 돌린다. 기존 테스트가 실패하면 테스트가 아니라 코드를 먼저 의심한다.",
            Set.of("EXEC_TEST_REGRESSION", "EXEC_NEW_TEST_FAILS", "EXEC_HEAD_BUILD_FAILED"), List.of("테스트 실패", "테스트가 실패", "테스트가 깨")),
    SIGNATURE("시그니처를 바꾸고 호출부를 안 고침",
            "메서드 시그니처(파라미터 · 반환 타입)를 바꾸면 테스트를 포함한 모든 호출부를 같은 PR 에서 고친다.",
            Set.of("ARITY_MISMATCH", "UNRESOLVED_METHOD"), List.of("시그니처", "호출부", "인자 수", "생성자 변경")),
    SECRET("비밀값 · 주소 하드코딩",
            "토큰 · 비밀번호 · 키 · 외부 서비스 주소를 코드에 쓰지 않는다. 설정(환경변수 · application.yml 의 ${...})으로 받는다.",
            Set.of(), List.of("하드코딩", "hardcod", "비밀", "secret", "api key", "토큰")),
    AUTH("권한 · 인증 검사 누락",
            "새 API 는 비슷한 기존 API 와 같은 권한 검사(@PreAuthorize · 소유자 확인 · SecurityConfig)를 갖춘다. permitAll 을 넓히지 않는다.",
            Set.of(), List.of("권한", "인증", "permitall", "소유자", "보안 설정", "접근 제어")),
    DEPENDENCY("의존성 · 빌드 설정 오류",
            "새 라이브러리는 실제로 있는 좌표 · 버전인지 확인하고 추가 이유를 적는다. 쓰지 않는 의존성을 넣지 않는다.",
            Set.of(), List.of("의존성", "artifact", "build.gradle", "pom.xml", "좌표", "라이브러리 버전")),
    MIGRATION("스키마 · 마이그레이션 불일치",
            "엔티티 · 컬럼을 바꾸면 마이그레이션 스크립트를 같은 PR 에 넣고, 지운 컬럼을 쓰는 코드가 남지 않았는지 확인한다.",
            Set.of(), List.of("마이그레이션", "migration", "컬럼", "스키마", "flyway", "ddl")),
    API_CONTRACT("API 응답 · 계약 변경",
            "API 응답 필드 · 상태 코드를 바꾸거나 지우면 하위 호환을 지키거나, PR 본문에 깨지는 클라이언트를 적는다.",
            Set.of(), List.of("응답 필드", "api 응답", "응답 dto", "하위 호환", "계약")),
    ERROR_HANDLING("예외 처리 누락 · 예외 삼킴",
            "예외를 삼키지 않는다. catch 하면 로그를 남기고 의미 있는 예외로 다시 던지거나 실패를 호출하는 쪽에 알린다.",
            Set.of(), List.of("예외를 무시", "예외 무시", "삼키", "catch", "예외 처리", "로그가 남지")),
    INTENT("PR 설명과 다른 변경",
            "PR 하나에는 목적 하나만 담는다. 설명에 없는 변경을 섞지 않고, 설명한 것은 빠짐없이 구현한다.",
            Set.of(), List.of("pr 설명", "pr 본문", "pr 목적", "목적과 무관", "의도", "요청하지 않은", "설명과 다르", "범위를 벗어")),
    UNTESTED("테스트가 지키지 않는 변경",
            "바꾼 코드는 테스트가 실제로 지나가야 한다. 테스트가 없는 곳을 바꾸면 테스트를 먼저 추가한다.",
            Set.of("EXEC_UNTESTED_CHANGE"), List.of("테스트가 없", "테스트 누락", "테스트를 추가", "커버리지")),
    CONCURRENCY("동시성 · 정합성 문제",
            "카운터 · 잔액 같은 값은 읽고-고치고-쓰기 대신 원자적 갱신(UPDATE … SET x = x + 1)이나 잠금을 쓴다.",
            Set.of(), List.of("동시성", "경쟁 조건", "race", "정합성", "원자적"));

    private final String label;
    private final String rule;
    private final Set<String> ruleIds;
    private final List<String> keywords;

    MistakeType(String label, String rule, Set<String> ruleIds, List<String> keywords) {
        this.label = label;
        this.rule = rule;
        this.ruleIds = ruleIds;
        this.keywords = keywords;
    }

    public String label() {
        return label;
    }

    public String rule() {
        return rule;
    }

    /** 맞는 유형이 없으면 null. 규칙 id → 제목 → 본문 순으로 본다 (본문은 다른 문제를 곁들여 말하는 경우가 많다) */
    public static MistakeType of(String ruleId, String title, String message) {
        for (MistakeType t : values()) {
            if (t.ruleIds.contains(ruleId)) {
                return t;
            }
        }
        MistakeType byTitle = byKeyword(title);
        return byTitle != null ? byTitle : byKeyword(message);
    }

    private static MistakeType byKeyword(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (MistakeType t : values()) {
            for (String k : t.keywords) {
                if (lower.contains(k)) {
                    return t;
                }
            }
        }
        return null;
    }
}
