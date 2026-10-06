package com.prguard.graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 꺼낸 소스 디렉터리에 GitNexus 를 돌려 파일 의존성 그래프 JSON 을 얻는다 (gitnexus/export.mjs). */
@Component
public class GraphBuilder {

    private static final int MAX_ERROR_CHARS = 2000;

    private final GraphProperties props;

    public GraphBuilder(GraphProperties props) {
        this.props = props;
    }

    public String build(Path sourceDir) {
        Path out = null;
        Path log = null;
        try {
            out = Files.createTempFile("repo-graph-", ".json");
            log = Files.createTempFile("repo-graph-", ".log");
            ProcessBuilder pb = new ProcessBuilder(List.of(props.node(), props.script().toAbsolutePath().toString(),
                    sourceDir.toAbsolutePath().toString(), out.toString(), String.valueOf(props.maxNodes())))
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            pb.environment().put("GRAPH_HEAP_MB", String.valueOf(props.heapMb()));
            Process process;
            try {
                process = pb.start();
            } catch (IOException e) {
                throw new GraphException("node 실행 실패 (" + props.node() + "): " + e.getMessage());
            }
            if (!process.waitFor(props.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new GraphException("그래프 생성 시간 초과 (" + props.timeout().toSeconds() + "s)");
            }
            if (process.exitValue() != 0) {
                throw new GraphException("그래프 생성 실패 (" + process.exitValue() + "): " + tail(log));
            }
            return Files.readString(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException("그래프 생성 중단");
        } finally {
            deleteQuietly(out);
            deleteQuietly(log);
        }
    }

    private static String tail(Path log) throws IOException {
        String text = Files.readString(log, StandardCharsets.UTF_8).strip();
        return text.length() > MAX_ERROR_CHARS ? text.substring(text.length() - MAX_ERROR_CHARS) : text;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 임시 파일이라 남아도 된다
        }
    }
}
