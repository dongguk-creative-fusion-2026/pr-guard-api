package com.prguard.review;

import java.util.List;

/**
 * 바뀐 메서드와 그 메서드가 기대던 기존 코드를 주고 "기존 코드가 지키던 규칙과 충돌하는가"만 따로 묻는다.
 * 일반 리뷰(diff 전체)와 달리 질문을 좁혀, diff 만 봐서는 안 보이는 의미 충돌을 찾는다.
 */
public interface ConflictReviewer {

    /** 꺼져 있으면 파이프라인이 단계를 건너뛴다 (OPENAI_API_KEY 없음 · 설정에서 끔). */
    boolean enabled();

    ConflictReview review(Input input);

    /**
     * @param context 바뀐 메서드별 기존 코드 조각 (조각마다 [C1] 같은 id)
     */
    record Input(String repo, int number, String title, String body, List<String> commitMessages, String context) {
    }

    ConflictReviewer DISABLED = new ConflictReviewer() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public ConflictReview review(Input input) {
            return new ConflictReview(List.of());
        }
    };
}
