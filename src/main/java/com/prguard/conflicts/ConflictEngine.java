package com.prguard.conflicts;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 번들한 MIT Python 엔진만 실행한다. 대상 레포의 코드/설정은 실행하지 않는다. */
@Component
public class ConflictEngine {
    private final ConflictProperties props;
    private final ObjectMapper mapper;

    public ConflictEngine(ConflictProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public ConflictReport.EngineResult analyze(Object input) {
        Path work = null;
        Process process = null;
        try {
            work = Files.createTempDirectory("pr-conflicts-");
            for (String name : List.of("runner.py", "pr_data.py", "conflict_detector.py")) {
                try (var resource = new ClassPathResource("prconflict/" + name).getInputStream()) {
                    Files.copy(resource, work.resolve(name));
                }
            }
            Files.writeString(work.resolve("input.json"), mapper.writeValueAsString(input));
            var pb = new ProcessBuilder(props.python(), "-I", work.resolve("runner.py").toString(),
                    work.resolve("input.json").toString(), work.resolve("output.json").toString())
                    .directory(work.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            var env = pb.environment();
            var inherited = new HashMap<>(env);
            env.clear();
            var allowed = Set.of("PATH", "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "TEMP", "TMP", "PATHEXT");
            inherited.forEach((k, v) -> { if (allowed.contains(k.toUpperCase(Locale.ROOT))) env.put(k, v); });
            process = pb.start();
            if (!process.waitFor(props.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("PR Conflict Detector 시간 초과");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("PR Conflict Detector 실패 (exit=" + process.exitValue() + ")");
            }
            Path output = work.resolve("output.json");
            if (!Files.isRegularFile(output) || Files.size(output) > 10 * 1024 * 1024) {
                throw new IllegalStateException("PR conflict 보고서 없음 또는 10 MiB 초과");
            }
            var result = mapper.readValue(Files.readString(output), ConflictReport.EngineResult.class);
            if (result.version() != 1 || result.conflicts() == null || result.clusters() == null
                    || result.conflicts().stream().anyMatch(ConflictReport.Pair::verified)) {
                throw new IllegalStateException("잘못된 PR conflict 보고서");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("PR Conflict Detector 실행/보고서 오류: Python 경로와 번들 파일을 확인하세요");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PR Conflict Detector 중단");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (work != null) {
                try (var files = Files.walk(work)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }
}
