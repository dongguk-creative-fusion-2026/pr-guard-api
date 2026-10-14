-- flaky 판별: 처음엔 실패했는데 그 클래스만 다시 돌리니 통과한 테스트 ["a.FooTest#x()", …]
ALTER TABLE test_runs ADD COLUMN flaky JSONB;
