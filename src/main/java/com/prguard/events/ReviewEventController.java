package com.prguard.events;

import com.prguard.common.ApiException;
import com.prguard.review.Review;
import com.prguard.review.ReviewRepository;
import com.prguard.review.ReviewStatus;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ReviewEventController {

    private static final Set<ReviewStatus> FINISHED = Set.of(ReviewStatus.DONE, ReviewStatus.FAILED,
            ReviewStatus.SUPERSEDED);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final ReviewRepository reviews;
    private final ReviewEventRepository events;
    private final ReviewEventBus bus;

    public ReviewEventController(ReviewRepository reviews, ReviewEventRepository events, ReviewEventBus bus) {
        this.reviews = reviews;
        this.events = events;
        this.bus = bus;
    }

    /**
     * 리뷰 진행 단계 스트림 (SSE). 저장된 이벤트를 먼저 보내고, 진행 중이면 끝날 때까지 이어서 보낸다.
     * 끝나면 "end" 이벤트를 보내고 닫는다.
     */
    @GetMapping(path = "/api/reviews/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable long id) {
        Review review = find(id);
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        bus.subscribe(id, emitter, FINISHED.contains(review.status()));
        return emitter;
    }

    /** 저장된 이벤트 전체 (다시보기용). */
    @GetMapping(path = "/api/reviews/{id}/events", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReviewEvent> list(@PathVariable long id) {
        find(id);
        return events.findByReview(id);
    }

    private Review find(long id) {
        return reviews.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "리뷰가 없습니다: " + id));
    }
}
