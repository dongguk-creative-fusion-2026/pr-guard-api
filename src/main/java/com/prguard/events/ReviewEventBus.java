package com.prguard.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
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
    private final Map<Long, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

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
        Subscriber subscriber = new Subscriber(emitter);
        if (!finished) {
            subscribers.computeIfAbsent(reviewId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
            Runnable remove = () -> unsubscribe(reviewId, subscriber);
            emitter.onCompletion(remove);
            emitter.onTimeout(remove);
            emitter.onError(e -> remove.run());
        }
        try {
            subscriber.send(() -> events.findByReview(reviewId).stream().map(ReviewEventBus::stageEvent).toList(),
                    finished);
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void publish(ReviewEvent event) {
        List<Subscriber> list = subscribers.get(event.reviewId());
        if (list == null) {
            return;
        }
        boolean end = event.stage() == Stage.REVIEW && event.status() != StageStatus.RUNNING;
        for (Subscriber subscriber : list) {
            try {
                subscriber.send(() -> List.of(stageEvent(event)), end);
            } catch (IOException | IllegalStateException e) {
                unsubscribe(event.reviewId(), subscriber);
            }
        }
        if (end) {
            subscribers.remove(event.reviewId());
        }
    }

    /** 프록시가 유휴 연결을 끊지 않게 주기적으로 주석 한 줄을 보낸다. */
    @Scheduled(fixedDelayString = "PT15S")
    public void heartbeat() {
        subscribers.forEach((reviewId, list) -> list.forEach(subscriber -> {
            try {
                subscriber.send(() -> List.of(SseEmitter.event().comment("ping")), false);
            } catch (IOException | IllegalStateException e) {
                unsubscribe(reviewId, subscriber);
            }
        }));
    }

    private void unsubscribe(long reviewId, Subscriber subscriber) {
        List<Subscriber> list = subscribers.get(reviewId);
        if (list != null) {
            list.remove(subscriber);
        }
    }

    private static SseEmitter.SseEventBuilder stageEvent(ReviewEvent event) {
        return SseEmitter.event().id(String.valueOf(event.id())).name("stage").data(event, MediaType.APPLICATION_JSON);
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

    /** 화면 연결 하나. 구독 · 발행 · 하트비트가 다른 스레드에서 보내므로 한 번에 하나씩 보낸다 */
    private static final class Subscriber {

        private final SseEmitter emitter;

        Subscriber(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /** 잠근 뒤 events 를 만들어 차례로 보내고, end 면 끝 이벤트를 보내고 닫는다 */
        synchronized void send(Supplier<List<SseEmitter.SseEventBuilder>> events, boolean end) throws IOException {
            for (SseEmitter.SseEventBuilder e : events.get()) {
                emitter.send(e);
            }
            if (end) {
                emitter.send(SseEmitter.event().name("end").data("{}", MediaType.APPLICATION_JSON));
                emitter.complete();
            }
        }
    }
}
