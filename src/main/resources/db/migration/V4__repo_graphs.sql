-- 레포 전체 파일 의존성 그래프 (GitNexus). 프로젝트당 하나, 등록할 때와 다시 만들기를 누를 때 만든다
CREATE TABLE repo_graphs (
    project_id  BIGINT      PRIMARY KEY REFERENCES projects (id) ON DELETE CASCADE,
    status      TEXT        NOT NULL,
    commit_sha  TEXT,
    graph       JSONB,
    error       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at  TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

-- 이미 등록된 프로젝트도 한 번 만든다
INSERT INTO repo_graphs (project_id, status) SELECT id, 'PENDING' FROM projects;
