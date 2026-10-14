package com.prguard.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prguard.review.ReviewProperties;
import com.prguard.review.ReviewRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReviewWorkerWakeTest {

    private final ReviewRepository reviews = mock(ReviewRepository.class);
    private final ReviewWorker worker = new ReviewWorker(reviews, null, null, null, null, null, null, null,
            new ReviewProperties(Duration.ofSeconds(10), 1000, Duration.ofMinutes(15)), null, null, null);

    @Test
    void wake_drainsOnAnotherThread() {
        when(reviews.claimNext(any())).thenReturn(Optional.empty());

        worker.wake();

        verify(reviews, timeout(2000)).claimNext(any());
    }

    @Test
    void wakeDuringDrain_runsOneMoreDrainAfterward() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        // 첫 claimNext 에서 멈춰 "도는 중"을 만든다
        when(reviews.claimNext(any())).thenAnswer(inv -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            inside.countDown();
            release.await(2, TimeUnit.SECONDS);
            running.decrementAndGet();
            return Optional.empty();
        }).thenAnswer(inv -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            running.decrementAndGet();
            return Optional.empty();
        });

        Thread scheduled = new Thread(worker::drain);
        scheduled.start();
        inside.await(2, TimeUnit.SECONDS);

        // 도는 중에 웹훅이 깨움 → 겹쳐 돌지 않고, 끝난 뒤 한 번 더 확인한다
        worker.wake();
        release.countDown();
        scheduled.join(2000);

        verify(reviews, timeout(2000).atLeast(2)).claimNext(any());
        assertThat(maxRunning.get()).isEqualTo(1);
    }
}
