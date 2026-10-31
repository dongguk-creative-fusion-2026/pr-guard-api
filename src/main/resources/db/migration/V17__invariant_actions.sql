ALTER TABLE invariant_runs
    ADD COLUMN workflow_repo TEXT,
    ADD COLUMN workflow_ref TEXT,
    ADD COLUMN workflow_run_id BIGINT,
    ADD COLUMN run_url TEXT,
    ADD COLUMN completed_seen_at TIMESTAMPTZ;

-- Previously running local executions cannot be resumed by the Actions collector.
UPDATE invariant_runs SET status = 'FAILED', finished_at = now(),
    result = '{"status":"execution_error","error":"execution backend changed to GitHub Actions"}'::jsonb
WHERE status = 'RUNNING';

-- Serialize dispatch across API instances, keeping the initial deployment inexpensive.
CREATE UNIQUE INDEX invariant_one_active_run ON invariant_runs ((true))
WHERE status IN ('QUEUED', 'RUNNING');
