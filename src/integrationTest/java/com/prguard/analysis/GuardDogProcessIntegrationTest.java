package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Exercises real OS pipes and process termination without Docker or registry network access. */
class GuardDogProcessIntegrationTest {
    private GuardDogFixtureClient client(String mode) {
        return new GuardDogFixtureClient(new GuardDogProperties(true, "fixture", Duration.ofSeconds(5),
                Duration.ofSeconds(10), 1, 1024, true), new ObjectMapper(), mode);
    }

    @Test void realChildProcessKeepsStderrSeparateFromJson() {
        var result = client("risk").scan("pypi", "fixture-risk", "1.0.0", Duration.ofSeconds(5));
        assertThat(result.at("/risks/0/severity").asText()).isEqualTo("high");
    }

    @Test void realChildProcessMalformedOutputFails() {
        assertThatThrownBy(() -> client("malformed").scan("pypi", "fixture-risk", "1.0.0", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JSON");
    }

    @Test void realChildProcessOversizedOutputFailsWithoutBlocking() {
        assertThatThrownBy(() -> client("oversized").scan("pypi", "fixture-risk", "1.0.0", Duration.ofSeconds(5)))
                .isInstanceOf(GuardDogException.class).hasMessageContaining("크기 제한")
                .satisfies(e -> assertThat(((GuardDogException) e).reason()).isEqualTo(GuardDogReport.Reason.OUTPUT_LIMIT));
    }

    @Test void realChildProcessTimeoutReturnsPromptly() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> client("timeout").scan("pypi", "fixture-risk", "1.0.0", Duration.ofMillis(200)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("시간 초과");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }
}
