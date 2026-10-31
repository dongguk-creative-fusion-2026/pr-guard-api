package com.prguard.analysis.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C. 보안: Semgrep 규칙(SQL Injection · 위험한 API · 인증 누락 …)으로 바뀐 파일을 base · head 에서 검사한다.
 * head 에만 있고 이번 PR 이 추가한 줄에 걸친 결과만 남긴다 (레포에 원래 있던 문제는 이번 PR 의 책임이 아니다).
 * 실행 파일이 없으면 건너뛰고 이유를 남긴다. 테스트 코드는 보지 않는다.
 */
@Component
public class SemgrepAnalyzer implements Analyzer {

    private static final Logger log = LoggerFactory.getLogger(SemgrepAnalyzer.class);
    private static final Pattern TEST_PATH = Pattern.compile("(^|/)(test|tests|__tests__|spec|fixtures?)/|Tests?\\.java$");
    private static final int MAX_SNIPPET_LINES = 8;
    private static final int MAX_MESSAGE = 600;
    private static final int MAX_STDERR = 400;

    private final SemgrepProperties props;
    private final ObjectMapper mapper;
    private volatile Boolean available;

    public SemgrepAnalyzer(SemgrepProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    /**
     * Semgrep 결과 하나.
     *
     * @param file    head 기준 파일 이름 (base 결과도 head 이름으로 옮겨 둔다)
     * @param snippet 걸린 코드. 라인 번호 대신 이것으로 base · head 의 같은 문제를 맞춘다
     */
    record Hit(String file, String checkId, int start, int end, String message, String severity, String category,
               String cwe, String snippet) {

        String anchor() {
            return "semgrep:" + checkId + ":" + normalize(snippet);
        }
    }

    @Override
    public String id() {
        return "semgrep";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        Optional<Checkout> checkout = ctx.checkout();
        if (!props.enabled() || checkout.isEmpty()) {
            return List.of();
        }
        List<ChangedFile> targets = ctx.files().stream()
                .filter(f -> !f.removed() && !f.patch().addedLines().isEmpty() && !TEST_PATH.matcher(f.filename()).find())
                .limit(props.maxFiles())
                .toList();
        if (targets.isEmpty()) {
            return List.of();
        }
        if (!available()) {
            ctx.notes().add("Semgrep 실행 파일(" + props.binary() + ")이 없어 Semgrep 검사를 건너뜀");
            return List.of();
        }
        Checkout c = checkout.get();
        // 실행 경로 → head 기준 파일 이름. base 와 head 를 한 번에 돌려 규칙을 한 번만 받는다
        Map<Path, String> head = new LinkedHashMap<>();
        Map<Path, String> base = new LinkedHashMap<>();
        for (ChangedFile f : targets) {
            Path h = c.headDir().resolve(f.filename()).toAbsolutePath().normalize();
            if (Files.isRegularFile(h)) {
                head.put(h, f.filename());
            }
            Path b = c.baseDir().resolve(f.previousFilename() != null ? f.previousFilename() : f.filename())
                    .toAbsolutePath().normalize();
            if (!"added".equals(f.status()) && Files.isRegularFile(b)) {
                base.put(b, f.filename());
            }
        }
        if (head.isEmpty()) {
            return List.of();
        }
        List<Path> all = new ArrayList<>(head.keySet());
        all.addAll(base.keySet());
        JsonNode out = run(all);
        logErrors(out, ctx);
        List<Hit> headHits = hits(out, head, SemgrepAnalyzer::snippet);
        List<Hit> baseHits = hits(out, base, SemgrepAnalyzer::snippet);
        Map<String, Set<Integer>> added = targets.stream()
                .collect(Collectors.toMap(ChangedFile::filename, f -> f.patch().addedLines(), (a, b) -> a));
        return findings(headHits, baseHits, added);
    }

    /** head 에만 있고 추가된 줄에 걸친 결과 → 지적 */
    static List<Finding> findings(List<Hit> head, List<Hit> base, Map<String, Set<Integer>> added) {
        Set<String> existing = base.stream().map(h -> h.file() + "|" + h.anchor()).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<Finding> result = new ArrayList<>();
        for (Hit h : head) {
            String key = h.file() + "|" + h.anchor();
            if (existing.contains(key) || !touches(h, added.getOrDefault(h.file(), Set.of())) || !seen.add(key)) {
                continue;
            }
            boolean security = "security".equalsIgnoreCase(h.category());
            String evidence = h.checkId() + (h.cwe() == null ? "" : " · " + h.cwe())
                    + (h.snippet().isBlank() ? "" : "\n" + h.snippet());
            result.add(new Finding("SEMGREP", security ? Category.SECURITY : Category.GENERAL, severity(h.severity()),
                    h.file(), h.start(), "Semgrep: " + shortName(h.checkId()), clip(h.message(), MAX_MESSAGE),
                    evidence, h.anchor(), Finding.TOOL));
        }
        return result;
    }

    /** 결과의 줄 범위가 이번 PR 이 추가한 줄과 겹치나 */
    private static boolean touches(Hit h, Set<Integer> added) {
        for (int line = h.start(); line <= Math.max(h.start(), h.end()); line++) {
            if (added.contains(line)) {
                return true;
            }
        }
        return false;
    }

    /** semgrep --json 결과에서 한쪽(names 에 있는 경로)만 */
    static List<Hit> hits(JsonNode out, Map<Path, String> names, Function<Path, String> snippets) {
        List<Hit> result = new ArrayList<>();
        for (JsonNode r : out.path("results")) {
            Path path = Path.of(r.path("path").asText()).toAbsolutePath().normalize();
            String file = names.get(path);
            if (file == null) {
                continue;
            }
            int start = r.path("start").path("line").asInt();
            int end = r.path("end").path("line").asInt(start);
            JsonNode extra = r.path("extra");
            JsonNode meta = extra.path("metadata");
            String snippet = snippets.apply(path);
            result.add(new Hit(file, r.path("check_id").asText(), start, end, extra.path("message").asText("").strip(),
                    extra.path("severity").asText("INFO"), meta.path("category").asText(null), cwe(meta.path("cwe")),
                    snippet == null ? "" : lines(snippet, start, end)));
        }
        return result;
    }

    private static String cwe(JsonNode node) {
        if (node.isArray() && !node.isEmpty()) {
            return node.get(0).asText();
        }
        return node.isTextual() ? node.asText() : null;
    }

    static Severity severity(String semgrep) {
        return switch (semgrep == null ? "" : semgrep.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "ERROR", "HIGH" -> Severity.MAJOR;
            case "WARNING", "MEDIUM" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }

    /** java.lang.security.audit.sqli.jdbc-sqli → jdbc-sqli */
    static String shortName(String checkId) {
        int dot = checkId.lastIndexOf('.');
        return dot < 0 ? checkId : checkId.substring(dot + 1);
    }

    private JsonNode run(List<Path> targets) {
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile("semgrep-", ".json");
            err = Files.createTempFile("semgrep-", ".log");
            List<String> cmd = new ArrayList<>(List.of(props.binary(), "scan", "--json", "--quiet", "--metrics=off",
                    "--disable-version-check", "--timeout", "30"));
            for (String config : props.configs()) {
                cmd.add("--config");
                cmd.add(config);
            }
            targets.forEach(t -> cmd.add(t.toString()));
            Process p = start(cmd, out, err);
            if (!p.waitFor(props.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("Semgrep 시간 초과 (" + props.timeout().toSeconds() + "초)");
            }
            // 0 = 결과 없음, 1 = 결과 있음. 그 밖은 규칙을 못 받았거나 실행 실패
            if (p.exitValue() > 1) {
                throw new IllegalStateException("Semgrep 실패 (종료 코드 " + p.exitValue() + "): "
                        + clip(Files.readString(err, StandardCharsets.UTF_8).strip(), MAX_STDERR));
            }
            return mapper.readTree(out.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Semgrep 실행 실패: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Semgrep 실행이 중단됨", e);
        } finally {
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    private static Process start(List<String> cmd, Path out, Path err) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
        pb.environment().put("SEMGREP_SEND_METRICS", "off");
        pb.environment().put("SEMGREP_ENABLE_VERSION_CHECK", "0");
        return pb.start();
    }

    private boolean available() {
        Boolean known = available;
        if (known != null) {
            return known;
        }
        boolean ok;
        try {
            Process p = new ProcessBuilder(props.binary(), "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            ok = p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) {
                p.destroyForcibly();
            }
        } catch (IOException e) {
            ok = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!ok) {
            log.info("Semgrep 실행 파일을 찾지 못함: {}", props.binary());
        }
        available = ok;
        return ok;
    }

    /** 파싱하지 못한 파일 같은 부분 실패는 결과를 버리지 않고 메모만 남긴다 */
    private static void logErrors(JsonNode out, AnalysisContext ctx) {
        int errors = out.path("errors").size();
        if (errors > 0) {
            ctx.notes().add("Semgrep 이 일부 파일을 검사하지 못함 (" + errors + "건)");
        }
    }

    private static String snippet(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 파일 내용에서 start~end 줄 (최대 몇 줄만) */
    static String lines(String content, int start, int end) {
        String[] all = content.split("\n", -1);
        int from = Math.max(1, start);
        int to = Math.min(all.length, Math.min(Math.max(start, end), from + MAX_SNIPPET_LINES - 1));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(all[i - 1].stripTrailing()).append('\n');
        }
        return sb.toString().strip();
    }

    /** 들여쓰기 · 줄바꿈만 다른 것은 같은 코드로 본다 */
    static String normalize(String snippet) {
        String s = snippet.replaceAll("\\s+", " ").strip();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 임시 파일이라 남아도 된다
            }
        }
    }
}
