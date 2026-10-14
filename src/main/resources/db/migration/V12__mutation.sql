-- 변경분 변이 테스트(PIT) 결과: 바뀐 메서드 안의 변이마다 상태와 잡은 테스트 [{target, line, mutator, status, killers, covering}]
ALTER TABLE test_runs ADD COLUMN mutation JSONB;
