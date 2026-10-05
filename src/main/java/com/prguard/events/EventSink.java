package com.prguard.events;

import java.util.Map;

/** 파이프라인이 단계 진행을 알리는 곳. 리뷰 하나에 묶여 있다. */
@FunctionalInterface
public interface EventSink {

    EventSink NONE = (stage, status, message, data) -> {
    };

    void emit(Stage stage, StageStatus status, String message, Map<String, ?> data);

    default void running(Stage stage, String message) {
        emit(stage, StageStatus.RUNNING, message, Map.of());
    }

    default void done(Stage stage, String message, Map<String, ?> data) {
        emit(stage, StageStatus.DONE, message, data);
    }

    default void failed(Stage stage, String message) {
        emit(stage, StageStatus.FAILED, message, Map.of());
    }

    default void skipped(Stage stage, String message) {
        emit(stage, StageStatus.SKIPPED, message, Map.of());
    }
}
