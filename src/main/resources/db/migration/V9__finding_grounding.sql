-- 지적마다 층별 근거 (코드 인덱스 · git 이력 · 실행 · AI) 와 상태 (CONFIRMED · AI_ONLY · REFUTED)
ALTER TABLE findings ADD COLUMN grounding JSONB;
