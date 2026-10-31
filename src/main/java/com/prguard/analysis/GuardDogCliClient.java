package com.prguard.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import static com.prguard.analysis.GuardDogReport.Reason.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class GuardDogCliClient implements GuardDogClient {
    private final GuardDogProperties props;
    private final ObjectMapper mapper;

    public GuardDogCliClient(GuardDogProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public JsonNode scan(String ecosystem, String name, String version, Duration timeout) {
        if (!DependencyManifest.validPackage(ecosystem, name, version)) {
            throw new IllegalArgumentException("Unsupported GuardDog package identifier");
        }
        // Each argument is passed directly; PR content never becomes a shell command or a local path.
        ProcessBuilder builder = new ProcessBuilder(List.of(props.executable(), ecosystem, "scan", name,
                "--version", version, "--output-format", "json"));
        // Do not pass API, database, GitHub or cloud credentials to the scanner.
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> !List.of("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
                "HOME", "USERPROFILE", "LANG", "LC_ALL", "SSL_CERT_FILE", "SSL_CERT_DIR",
                "GUARDDOG_TOP_PACKAGES_CACHE_LOCATION")
                .contains(key.toUpperCase(java.util.Locale.ROOT)));
        Process process = null;
        Path directory = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // GuardDog treats an existing target path as local code. An empty working directory
            // prevents a registry name such as "src" from accidentally selecting API source.
            directory = Files.createTempDirectory("prguard-guarddog-");
            builder.directory(directory.toFile());
            process = start(builder);
            long deadline = System.nanoTime() + timeout.toNanos();
            Process running = process;
            Future<byte[]> stdout = readers.submit(() -> read(running.getInputStream()));
            Future<byte[]> stderr = readers.submit(() -> read(running.getErrorStream()));
            if (!process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS)) {
                stop(process);
                throw new GuardDogException(TIMEOUT, "GuardDog 실행 시간 초과");
            }
            byte[] output = stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) {
                throw new GuardDogException(EXIT_ERROR, "GuardDog 종료 코드 " + process.exitValue());
            }
            JsonNode result = mapper.readTree(output);
            if (result == null || !result.isObject() || !result.path("issues").isIntegralNumber()
                    || result.path("issues").asLong() < 0 || !result.path("errors").isObject()) {
                throw new GuardDogException(MALFORMED_OUTPUT, "GuardDog JSON 형식 불일치");
            }
            return result;
        } catch (InterruptedException e) {
            if (process != null) stop(process);
            Thread.currentThread().interrupt();
            throw new GuardDogException(INTERRUPTED, "GuardDog 실행 중단", e);
        } catch (JsonProcessingException e) {
            throw new GuardDogException(MALFORMED_OUTPUT, "GuardDog JSON 읽기 실패", e);
        } catch (IOException e) {
            throw new GuardDogException(START_FAILED, "GuardDog 실행 환경을 준비하지 못했습니다.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutputLimitException) {
                throw new GuardDogException(OUTPUT_LIMIT, "GuardDog 출력 크기 제한 초과", e);
            }
            throw new GuardDogException(EXECUTION_FAILED, "GuardDog 출력 읽기 실패", e);
        } catch (TimeoutException e) {
            throw new GuardDogException(TIMEOUT, "GuardDog 출력 대기 시간 초과", e);
        } finally {
            if (process != null) stop(process);
            readers.shutdownNow();
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // Never recursively delete unexpected scanner artifacts.
                }
            }
        }
    }

    Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    private byte[] read(InputStream stream) throws IOException {
        try (stream; var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (output.size() + count > props.maxOutputBytes()) {
                    // Continue draining to avoid deadlocking the process on a full pipe.
                    stream.transferTo(java.io.OutputStream.nullOutputStream());
                    throw new OutputLimitException();
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
    }

    private static final class OutputLimitException extends IOException { }
}
