CREATE TABLE invariant_runs (
    id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    pr_number INT NOT NULL,
    head_sha TEXT NOT NULL,
    base_sha TEXT NOT NULL,
    candidate JSONB NOT NULL,
    status TEXT NOT NULL,
    result JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (project_id, pr_number) REFERENCES pull_requests(project_id, number) ON DELETE CASCADE
);
