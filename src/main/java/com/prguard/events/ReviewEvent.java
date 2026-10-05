package com.prguard.events;

import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.OffsetDateTime;

/**
 * 단계 하나의 상태 변화.
 *
 * @param id   전역 순번. 같은 리뷰 안에서 순서를 정하고 중복을 거르는 데 쓴다
 * @param data 단계별 수치와 결과물 (JSON 그대로)
 */
public record ReviewEvent(long id, long reviewId, Stage stage, StageStatus status, String message,
                          @JsonRawValue String data, OffsetDateTime at) {
}
