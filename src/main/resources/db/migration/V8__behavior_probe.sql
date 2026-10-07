-- 동작 diff: 관측 테스트가 입력별로 남긴 결과 [{probe, label, out}]
ALTER TABLE test_runs ADD COLUMN probe JSONB;
