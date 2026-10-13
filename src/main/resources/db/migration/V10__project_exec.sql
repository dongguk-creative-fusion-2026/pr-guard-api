-- 프로젝트별 실행 검증: 켜면 PR 마다 base · head 테스트를 실제로 돌린다 (리뷰가 몇 분 걸린다). 기본은 끔
ALTER TABLE projects
    ADD COLUMN exec_enabled BOOLEAN NOT NULL DEFAULT false;
