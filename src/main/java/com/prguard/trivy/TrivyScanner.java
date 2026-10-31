package com.prguard.trivy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrivyScanner {
    private static final long MAX_REPORT_BYTES = 20 * 1024 * 1024;
    private final TrivyProperties props;
    private final TrivyProcess process;
    private final TrivyReportParser parser;

    public TrivyScanner(TrivyProperties props, TrivyProcess process, TrivyReportParser parser) {
        this.props = props;
        this.process = process;
        this.parser = parser;
    }

    public record Pair(TrivyReportParser.Report base, TrivyReportParser.Report head) { }

    /** 같은 JVM 의 비교 도중 DB 가 갱신되지 않게 직렬화한다. cache-dir 은 인스턴스별로 독점한다. */
    public synchronized Pair scan(Path base, Path head) {
        Path work = null;
        try {
            Files.createDirectories(props.cacheDir());
            work = Files.createTempDirectory(props.cacheDir(), "scan-");
            Files.writeString(work.resolve("config.yaml"), "{}\n");
            Files.writeString(work.resolve("ignore"), "");
            run(work, "취약점 DB 준비", List.of("--download-db-only"));
            if (hasJavaArchive(base) || hasJavaArchive(head)) {
                run(work, "Java DB 준비", List.of("--download-java-db-only"));
            }
            // 둘 다 성공하고 JSON 도 유효할 때만 비교 결과를 돌려준다.
            return new Pair(scanSide(base, work, "base"), scanSide(head, work, "head"));
        } catch (IOException e) {
            throw new IllegalStateException("Trivy 작업 파일을 읽거나 쓸 수 없습니다");
        } finally {
            cleanup(work);
        }
    }

    private TrivyReportParser.Report scanSide(Path source, Path work, String side) throws IOException {
        Path output = work.resolve(side + ".json");
        run(work, side + " 검사", List.of(
                "--scanners", "vuln,misconfig", "--misconfig-scanners", "dockerfile,kubernetes",
                "--severity", String.join(",", props.severities()), "--format", "json",
                "--output", output.toString(), "--exit-code", "0", "--list-all-pkgs=false",
                "--include-dev-deps", "--skip-db-update", "--skip-java-db-update", "--skip-check-update",
                "--offline-scan", "--ignorefile", work.resolve("ignore").toString(),
                "--skip-dirs", "**/.git,**/node_modules,**/.gradle", "--skip-files", "**/gradle-wrapper.jar",
                source.toAbsolutePath().normalize().toString()));
        if (!Files.isRegularFile(output) || Files.size(output) > MAX_REPORT_BYTES) {
            throw new IllegalStateException("Trivy " + side + " 보고서 없음 또는 20 MiB 초과");
        }
        return parser.parse(Files.readString(output), source);
    }

    private void run(Path work, String step, List<String> options) {
        List<String> command = new ArrayList<>(List.of(props.binary(), "filesystem", "--config",
                work.resolve("config.yaml").toString(), "--cache-dir", props.cacheDir().toString(),
                "--timeout", props.timeout().toSeconds() + "s", "--no-progress", "--skip-version-check",
                "--disable-telemetry"));
        command.addAll(options);
        process.run(List.copyOf(command), work, props.timeout(), step);
    }

    private static boolean hasJavaArchive(Path source) throws IOException {
        try (var files = Files.find(source, Integer.MAX_VALUE, (path, attrs) -> attrs.isRegularFile()
                && !path.getFileName().toString().equals("gradle-wrapper.jar")
                && !source.relativize(path).toString().replace('\\', '/')
                        .matches("(^|.*/)(\\.git|node_modules|\\.gradle)/.*")
                && path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).matches(".*\\.(jar|war|par|ear)"))) {
            return files.findAny().isPresent();
        }
    }

    private static void cleanup(Path work) {
        if (work == null) {
            return;
        }
        try (var files = Files.walk(work)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }
}
