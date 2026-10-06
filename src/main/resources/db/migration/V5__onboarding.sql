-- 프로젝트별 리뷰 설정과 온보딩 완료 시각
ALTER TABLE projects
    ADD COLUMN comment_enabled BOOLEAN NOT NULL DEFAULT true,
    -- MAJOR 가 이 개수 이상이면 "수정 후 머지". NULL 이면 서버 기본값 (prguard.verdict.major-threshold)
    ADD COLUMN major_threshold INT,
    ADD COLUMN onboarded_at    TIMESTAMPTZ;

-- 이미 쓰던 프로젝트는 온보딩을 다시 시키지 않는다
UPDATE projects SET onboarded_at = created_at;

-- 그래프 워크플로 진행 단계 (등록 화면에서 실시간으로 보여 준다)와 Actions 실행 주소
ALTER TABLE repo_graphs
    ADD COLUMN progress JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN run_url  TEXT;
