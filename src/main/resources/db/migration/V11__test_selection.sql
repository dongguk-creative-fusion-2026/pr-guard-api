-- 영향 테스트 선택: PR 이 바꾼 코드를 지나가는 테스트만 돌린다
ALTER TABLE test_runs
    -- 러너가 받아 가는 실행 범위: PENDING(고르는 중) → ALL(전체) | FILTER(test_filter 의 클래스만)
    -- 이 열이 생기기 전 실행은 모두 전체 실행이었다
    ADD COLUMN selection_state TEXT NOT NULL DEFAULT 'ALL',
    -- FILTER 일 때 돌릴 테스트 클래스 ["com.a.FooTest", …]
    ADD COLUMN test_filter     JSONB,
    -- 왜 골랐고 왜 걸렀는지 (화면의 테스트 선택)
    ADD COLUMN selection       JSONB;
