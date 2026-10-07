-- 증거 테스트와 호출 기록
ALTER TABLE test_runs
    -- 러너에 넘길 증거 테스트: PENDING(만드는 중) → READY(있음) | NONE(없음)
    ADD COLUMN extra_state      TEXT NOT NULL DEFAULT 'PENDING',
    -- [{path, className, target, intent, code}]
    ADD COLUMN extra_files      JSONB,
    -- 증거 테스트가 컴파일되지 않아 빼고 돌렸으면 그 이유
    ADD COLUMN evidence_dropped TEXT,
    -- 테스트가 도는 동안 실제로 일어난 호출 {methods, counts, edges, tests}
    ADD COLUMN trace            JSONB;
