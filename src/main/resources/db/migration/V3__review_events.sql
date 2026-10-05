-- 리뷰 파이프라인 단계별 진행 기록. 화면에서 실시간으로 보여 주고, 끝난 뒤에는 다시보기에 쓴다
CREATE TABLE review_events (
    id        BIGSERIAL PRIMARY KEY,
    review_id BIGINT      NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    stage     TEXT        NOT NULL,
    status    TEXT        NOT NULL,
    message   TEXT,
    data      JSONB,
    at        TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX review_events_review_idx ON review_events (review_id, id);
