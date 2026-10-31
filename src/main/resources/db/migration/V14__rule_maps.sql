-- 규칙 지도: 커밋 하나의 코드가 지키는 규칙 (예외 · null 반환 · 검사 · 정렬 · 원자적 갱신 · 필드 제약 · 관례).
-- 리뷰할 때 PR 의 base 와 최신 기본 브랜치 커밋 것을 만들어 두고, 같은 커밋이면 다시 쓴다.
-- version 은 뽑는 방식(RuleExtractor.VERSION). 방식이 바뀌면 새로 만든다
CREATE TABLE rule_maps (
    repo       TEXT        NOT NULL,
    commit_sha TEXT        NOT NULL,
    version    INT         NOT NULL,
    rules      JSONB       NOT NULL,
    rule_count INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (repo, commit_sha, version)
);
