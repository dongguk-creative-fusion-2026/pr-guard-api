CREATE TABLE projects (
    id             BIGSERIAL PRIMARY KEY,
    owner          TEXT        NOT NULL,
    name           TEXT        NOT NULL,
    html_url       TEXT        NOT NULL,
    default_branch TEXT        NOT NULL,
    -- 열린 PR 목록 응답의 ETag. 변경이 없으면 GitHub 이 304 를 준다
    pulls_etag     TEXT,
    last_polled_at TIMESTAMPTZ,
    last_poll_error TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner, name)
);

CREATE TABLE pull_requests (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number      INT         NOT NULL,
    title       TEXT        NOT NULL,
    author      TEXT        NOT NULL,
    html_url    TEXT        NOT NULL,
    head_sha    TEXT        NOT NULL,
    head_ref    TEXT        NOT NULL,
    base_ref    TEXT        NOT NULL,
    state       TEXT        NOT NULL,
    draft       BOOLEAN     NOT NULL DEFAULT false,
    -- 요약 코멘트는 PR 당 하나만 두고 계속 고친다
    comment_id  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, number)
);

CREATE TABLE reviews (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    pr_number   INT         NOT NULL,
    head_sha    TEXT        NOT NULL,
    status      TEXT        NOT NULL,
    reviewer    TEXT,
    result      TEXT,
    error       TEXT,
    comment_url TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    -- 폴링이 겹쳐도 같은 커밋 리뷰는 한 번만 생긴다
    UNIQUE (project_id, pr_number, head_sha)
);

CREATE INDEX reviews_pending_idx ON reviews (id) WHERE status IN ('PENDING', 'RUNNING');
