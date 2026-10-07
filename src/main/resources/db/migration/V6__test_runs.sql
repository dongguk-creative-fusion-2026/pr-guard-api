-- 실행 검증: 리뷰마다 base · head 에서 테스트를 한 번씩 돌린 기록
CREATE TABLE test_runs (
    id          BIGSERIAL PRIMARY KEY,
    review_id   BIGINT      REFERENCES reviews (id) ON DELETE CASCADE,
    side        TEXT        NOT NULL,
    sha         TEXT        NOT NULL,
    -- QUEUED → STARTED → DONE | FAILED
    status      TEXT        NOT NULL,
    -- 러너가 콜백할 때 쓰는 일회용 토큰의 SHA-256
    token_hash  TEXT        NOT NULL,
    -- 실행 이름 (쿠버네티스 Job 또는 Docker 컨테이너)
    runner_name TEXT,
    -- 러너가 알린 진행 단계 (cloning, building …)와 메시지
    phase       TEXT,
    message     TEXT,
    exit_code   INT,
    tests       INT,
    failures    INT,
    errors      INT,
    skipped     INT,
    -- 테스트 케이스별 결과 [{name, status, message}]
    results     JSONB,
    log_tail    TEXT,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX test_runs_review_idx ON test_runs (review_id);
