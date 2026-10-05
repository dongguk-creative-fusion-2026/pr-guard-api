package com.prguard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 단계 이벤트를 DB 에 남기고, 그 리뷰를 보고 있는 화면(SSE 구독자)에 바로 보낸다.
 * 서버 인스턴스가 하나라는 전제 (구독자는 메모리에 있다).
 */
@Component
public class ReviewEventBus {

    private static final Logger log = LoggerFactory.getLogger(ReviewEventBus.class);

    private final ReviewEventRepository events;
    private final ObjectMapper mapper;
    private final Map<Long, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public ReviewEventBus(ReviewEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    /** 리뷰 하나에 묶인 sink. 이벤트 기록 실패는 리뷰를 멈추지 않는다. */
    public EventSink sinkFor(long reviewId) {
        return (stage, status, message, data) -> {
            try {
                publish(events.insert(reviewId, stage, status, message, json(data)));
            } catch (RuntimeException e) {
                log.warn("이벤트 기록 실패 review={} {}: {}", reviewId, stage, e.toString());
            }
        };
    }

    /**
     * 먼저 구독자로 등록하고 나서 저장된 이벤트를 보낸다. 그 사이에 생긴 이벤트는 두 번 갈 수 있으므로
     * 화면은 id 로 중복을 거른다.
     */
    public void subscribe(long reviewId, SseEmitter emitter, boolean finished) {
        if (!finished) {
            subscribers.computeIfAbsent(reviewId, k -> new CopyOnWriteArrayList<>()).add(emitter);
            Runnable remove = () -> unsubscribe(reviewId, emitter);
            emitter.onCompletion(remove);
            emitter.onTimeout(remove);
            emitter.onError(e -> remove.run());
        }
        try {
            synchronized (emitter) {
                for (ReviewEvent e : events.findByReview(reviewId)) {
                    emitter.send(SseEmitter.event().id(String.valueOf(e.id())).name("stage")
                            .data(e, MediaType.APPLICATION_JSON));
                }
                if (finished) {
                    emitter.send(SseEmitter.event().name("end").data("{}", MediaType.APPLICATION_JSON));
                    emitter.complete();
                }
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void publish(ReviewEvent event) {
        List<SseEmitter> list = subscribers.get(event.reviewId());
        if (list == null) {
            return;
        }
        boolean end = event.stage() == Stage.REVIEW && event.status() != StageStatus.RUNNING;
        for (SseEmitter emitter : list) {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().id(String.valueOf(event.id())).name("stage")
                            .data(event, MediaType.APPLICATION_JSON));
                    if (end) {
                        emitter.send(SseEmitter.event().name("end").data("{}", MediaType.APPLICATION_JSON));
                        emitter.complete();
                    }
                }
            } catch (IOException | IllegalStateException e) {
                unsubscribe(event.reviewId(), emitter);
            }
        }
        if (end) {
            subscribers.remove(event.reviewId());
        }
    }

    /** 프록시가 유휴 연결을 끊지 않게 주기적으로 주석 한 줄을 보낸다. */
    @Scheduled(fixedDelayString = "PT15S")
    public void heartbeat() {
        subscribers.forEach((reviewId, list) -> list.forEach(emitter -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (IOException | IllegalStateException e) {
                unsubscribe(reviewId, emitter);
            }
        }));
    }

    private void unsubscribe(long reviewId, SseEmitter emitter) {
        List<SseEmitter> list = subscribers.get(reviewId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    private String json(Map<String, ?> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
