package com.prguard.analysis;

import java.util.List;

/**
 * 지적 하나를 분석의 각 층이 어떻게 뒷받침하는지. LLM 의 말을 그대로 믿지 않으려고 둔다.
 *
 * <p>층은 아래에서 위로 코드 인덱스 → git 이력 → 실행 · 런타임 → AI 판단. 인덱스와 어긋나는 AI 지적은
 * {@link Status#REFUTED} 가 되어 판정에서 빠진다.
 *
 * @param kind    AI 지적의 종류 (LLM 이 고른 값). 결정적 검사의 지적이면 null
 * @param symbols 지적이 근거로 삼는 코드 이름 (예: {@code Post#getUpdatedAt}, {@code @Valid})
 * @param layers  층별 판단. 인덱스 · 이력 · 실행 · AI 순서
 */
public record Grounding(Status status, String kind, List<String> symbols, List<Layer> layers) {

    public enum Status {
        /** AI 가 아닌 층(인덱스 · 실행)이 같은 문제를 확인했다 */
        CONFIRMED,
        /** AI 만 주장한다. 다른 층은 맥락만 있거나 정보가 없다 */
        AI_ONLY,
        /** 코드 인덱스와 어긋난다. 판정과 PR 라인 코멘트에서 뺀다 */
        REFUTED
    }

    public enum LayerId {
        INDEX,
        HISTORY,
        RUNTIME,
        AI
    }

    public enum Mark {
        /** 이 층이 같은 문제를 스스로 찾았거나 증명했다 */
        CONFIRMED,
        /** 이 층에 관련 맥락이 있다 (호출부, 변경 이력, 테스트 경로 …) */
        RELATED,
        /** 이 층의 사실과 어긋난다 */
        REFUTED,
        /** 이 층에는 판단할 정보가 없다 */
        NONE
    }

    /** @param note 사람이 읽는 근거 한 줄 */
    public record Layer(LayerId layer, Mark mark, String note) {
    }

    /** LLM 지적이 처음 만들어질 때: 종류와 근거 이름만 있고 층은 아직 비어 있다. */
    public static Grounding claim(String kind, List<String> symbols) {
        return new Grounding(Status.AI_ONLY, kind, List.copyOf(symbols), List.of());
    }

    public boolean refuted() {
        return status == Status.REFUTED;
    }

    public Mark mark(LayerId id) {
        return layers.stream().filter(l -> l.layer() == id).map(Layer::mark).findFirst().orElse(Mark.NONE);
    }
}
