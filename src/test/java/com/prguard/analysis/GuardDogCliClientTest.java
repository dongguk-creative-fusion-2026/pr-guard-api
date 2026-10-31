package com.prguard.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GuardDogCliClientTest {
    final Process process = mock(Process.class);
    ProcessBuilder captured;
    final GuardDogCliClient client = new GuardDogCliClient(GuardDogAnalyzerTest.props(true, 20), new ObjectMapper()) {
        @Override Process start(ProcessBuilder builder) throws IOException {
            captured = builder;
            return process;
        }
    };

    void output(String json, int exit) throws Exception {
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream("progress log".getBytes(StandardCharsets.UTF_8)));
        when(process.waitFor(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
        when(process.exitValue()).thenReturn(exit);
        when(process.descendants()).thenAnswer(ignored -> Stream.empty());
    }

    @Test void cliArgumentsAndEnvironmentAreRestrictedAndStderrIsSeparate() throws Exception {
        output("{\"issues\":0,\"errors\":{},\"risks\":[]}", 0);
        assertThat(client.scan("npm", "@scope/pkg", "1.2.3", Duration.ofSeconds(1)).path("issues").asInt()).isZero();
        assertThat(captured.command()).containsExactly("guarddog", "npm", "scan", "@scope/pkg", "--version",
                "1.2.3", "--output-format", "json");
        assertThat(captured.environment()).doesNotContainKeys("GITHUB_TOKEN", "OPENAI_API_KEY", "PGPASSWORD", "AWS_ACCESS_KEY_ID");
        assertThat(captured.directory().getName()).startsWith("prguard-guarddog-");
        assertThat(captured.directory()).doesNotExist();
    }

    @Test void nonZeroExitAndInvalidOrTruncatedOutputAreFailures() throws Exception {
        for (var testCase : List.of(List.of("{\"issues\":0,\"errors\":{}}", "1"),
                List.of("not json", "0"), List.of("{}", "0"), List.of("x".repeat(1100), "0"))) {
            output(testCase.get(0), Integer.parseInt(testCase.get(1)));
            assertThatThrownBy(() -> client.scan("pypi", "requests", "2.31.0", Duration.ofSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void timeoutTerminatesProcessAndChildren() throws Exception {
        output("", 0);
        when(process.waitFor(anyLong(), any())).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        ProcessHandle child = mock(ProcessHandle.class);
        when(process.descendants()).thenAnswer(ignored -> Stream.of(child));
        assertThatThrownBy(() -> client.scan("npm", "pkg", "1.0.0", Duration.ofMillis(1)))
                .hasMessageContaining("시간 초과");
        verify(process, atLeastOnce()).destroyForcibly();
        verify(child, atLeastOnce()).destroyForcibly();
    }

    @Test void invalidIdentifierNeverStartsProcess() {
        assertThatThrownBy(() -> client.scan("npm", "--help", "1.0.0", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(captured).isNull();
    }

    @Test void outputFailuresHaveStableMachineReadableReasons() throws Exception {
        output("{\"issues\":0,\"errors\":{}}", 2);
        assertReason(GuardDogReport.Reason.EXIT_ERROR);
        output("not JSON", 0);
        assertReason(GuardDogReport.Reason.MALFORMED_OUTPUT);
        output("x".repeat(1100), 0);
        assertReason(GuardDogReport.Reason.OUTPUT_LIMIT);
    }

    @Test void startFailureDoesNotExposeRawSystemDiagnostics() {
        var failing = new GuardDogCliClient(GuardDogAnalyzerTest.props(true, 20), new ObjectMapper()) {
            @Override Process start(ProcessBuilder builder) throws IOException {
                throw new IOException("private-system-path?token=private-token");
            }
        };
        assertThatThrownBy(() -> failing.scan("pypi", "pkg", "1.0.0", Duration.ofSeconds(1)))
                .isInstanceOf(GuardDogException.class)
                .hasMessageNotContaining("private-system-path").hasMessageNotContaining("private-token")
                .satisfies(e -> assertThat(((GuardDogException) e).reason()).isEqualTo(GuardDogReport.Reason.START_FAILED));
    }

    private void assertReason(GuardDogReport.Reason reason) {
        assertThatThrownBy(() -> client.scan("pypi", "pkg", "1.0.0", Duration.ofSeconds(1)))
                .isInstanceOf(GuardDogException.class)
                .satisfies(e -> assertThat(((GuardDogException) e).reason()).isEqualTo(reason));
    }

    @Test void interruptionIsPreservedAndProcessIsTerminated() throws Exception {
        output("", 0);
        when(process.waitFor(anyLong(), any())).thenThrow(new InterruptedException());
        when(process.isAlive()).thenReturn(true);
        try {
            assertThatThrownBy(() -> client.scan("npm", "pkg", "1.0.0", Duration.ofSeconds(1)))
                    .hasMessageContaining("중단");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(process, atLeastOnce()).destroyForcibly();
        } finally {
            Thread.interrupted();
        }
    }
}
