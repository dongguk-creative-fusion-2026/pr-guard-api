-- 같은 커밋도 다시 리뷰할 수 있게 실행 차수(run)를 둔다. 폴링이 만드는 리뷰는 run=1
ALTER TABLE reviews ADD COLUMN run INT NOT NULL DEFAULT 1;
ALTER TABLE reviews DROP CONSTRAINT reviews_project_id_pr_number_head_sha_key;
ALTER TABLE reviews ADD CONSTRAINT reviews_commit_run_key UNIQUE (project_id, pr_number, head_sha, run);

-- 판정은 지적 사항으로 코드가 계산한 값
ALTER TABLE reviews ADD COLUMN verdict TEXT;
-- LLM 이 쓴 변경 요약
ALTER TABLE reviews ADD COLUMN summary TEXT;
-- 분석에 쓴 재료 요약 (바뀐 메서드, 호출부, 이력 통계 등)
ALTER TABLE reviews ADD COLUMN context JSONB;

ALTER TABLE pull_requests ADD COLUMN body TEXT;

CREATE TABLE findings (
    id          BIGSERIAL PRIMARY KEY,
    review_id   BIGINT NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    rule_id     TEXT   NOT NULL,
    category    TEXT   NOT NULL,
    severity    TEXT   NOT NULL,
    file        TEXT,
    line        INT,
    title       TEXT   NOT NULL,
    message     TEXT   NOT NULL,
    evidence    TEXT,
    fingerprint TEXT   NOT NULL,
    source      TEXT   NOT NULL
);

CREATE INDEX findings_review_idx ON findings (review_id);

-- PR 라인 코멘트를 같은 지적으로 여러 번 달지 않으려고 남긴다
CREATE TABLE posted_comments (
    id              BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL REFERENCES pull_requests (id) ON DELETE CASCADE,
    fingerprint     TEXT   NOT NULL,
    comment_id      BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (pull_request_id, fingerprint)
);
