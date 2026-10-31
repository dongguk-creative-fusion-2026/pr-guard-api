package com.prguard.trivy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyScannerTest {
    @TempDir Path temp;
    private final TrivyProcess process = mock(TrivyProcess.class);
    private static final String EMPTY = "{\"SchemaVersion\":2,\"ArtifactType\":\"filesystem\",\"Results\":[]}";

    @Test
    void refreshesDatabasesOnceThenFreezesBothScansAndIgnoresRepositoryConfig() throws Exception {
        Files.createDirectories(temp.resolve("base with spaces"));
        Files.writeString(temp.resolve("base with spaces/app.jar"), "archive placeholder for mocked CLI");
        List<List<String>> calls = new ArrayList<>();
        doAnswer(call -> {
            List<String> command = call.getArgument(0);
            Path work = call.getArgument(1);
            calls.add(command);
            assertThat(Files.readString(work.resolve("config.yaml"))).isEqualTo("{}\n");
            assertThat(Files.readString(work.resolve("ignore"))).isEmpty();
            if (command.contains("--output")) {
                assertThat(command).contains("--skip-db-update", "--skip-java-db-update", "--skip-check-update",
                        "--offline-scan", "vuln,misconfig", "--ignorefile", "--include-dev-deps");
                Files.writeString(Path.of(command.get(command.indexOf("--output") + 1)), EMPTY);
            }
            return null;
        }).when(process).run(anyList(), any(), any(), anyString());
        var pair = scanner().scan(temp.resolve("base with spaces"), temp.resolve("head"));
        assertThat(pair.head().findings()).isEmpty();
        assertThat(calls).hasSize(4);
        assertThat(calls.get(0)).contains("--download-db-only");
        assertThat(calls.get(1)).contains("--download-java-db-only");
        assertThat(calls.get(2).getLast()).endsWith("base with spaces");
        assertThat(calls.get(3).getLast()).endsWith("head");
        assertClean();
    }

    @Test
    void doesNotDownloadJavaArchiveDatabaseForLockfileOnlyRepositories() throws Exception {
        Files.createDirectories(temp.resolve("head/gradle/wrapper"));
        Files.writeString(temp.resolve("head/gradle/wrapper/gradle-wrapper.jar"), "wrapper placeholder");
        doAnswer(call -> {
            List<String> command = call.getArgument(0);
            assertThat(command).doesNotContain("--download-java-db-only");
            if (command.contains("--output")) {
                assertThat(command).contains("--skip-files", "**/gradle-wrapper.jar");
                Files.writeString(Path.of(command.get(command.indexOf("--output") + 1)), EMPTY);
            }
            return null;
        }).when(process).run(anyList(), any(), any(), anyString());
        scanner().scan(temp.resolve("base"), temp.resolve("head"));
        verify(process, times(3)).run(anyList(), any(), any(), anyString());
    }

    @Test
    void failedBaseStopsComparisonAndCleansTemporaryReports() throws Exception {
        doAnswer(call -> {
            List<String> command = call.getArgument(0);
            if (command.contains("--output")) {
                throw new IllegalStateException("Trivy base 검사 실패");
            }
            return null;
        }).when(process).run(anyList(), any(), any(), anyString());
        assertThatThrownBy(() -> scanner().scan(temp.resolve("base"), temp.resolve("head")))
                .hasMessageContaining("base 검사 실패");
        verify(process, times(2)).run(anyList(), any(), any(), anyString());
        assertClean();
    }

    @Test
    void missingOrMalformedReportIsNotAnEmptySuccessfulScan() throws Exception {
        assertThatThrownBy(() -> scanner().scan(temp.resolve("base"), temp.resolve("head")))
                .hasMessageContaining("보고서 없음");
        doAnswer(call -> {
            List<String> command = call.getArgument(0);
            if (command.contains("--output")) {
                Files.writeString(Path.of(command.get(command.indexOf("--output") + 1)), "{}");
            }
            return null;
        }).when(process).run(anyList(), any(), any(), anyString());
        assertThatThrownBy(() -> scanner().scan(temp.resolve("base"), temp.resolve("head")))
                .hasMessageContaining("report required");
        assertClean();
    }

    private TrivyScanner scanner() throws Exception {
        Files.createDirectories(temp.resolve("base"));
        Files.createDirectories(temp.resolve("head"));
        return new TrivyScanner(new TrivyProperties(true, "trivy", temp.resolve("cache"), Duration.ofSeconds(5), null),
                process, new TrivyReportParser(new ObjectMapper()));
    }

    private void assertClean() throws Exception {
        try (var files = Files.list(temp.resolve("cache"))) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("scan-")).toList()).isEmpty();
        }
    }
}
