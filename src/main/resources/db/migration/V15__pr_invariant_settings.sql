-- 검증할 규칙 초안은 PR마다 저장한다. 실행 및 자동 판정에는 아직 사용하지 않는다.
ALTER TABLE pull_requests ADD COLUMN invariant_rules TEXT NOT NULL DEFAULT '';
