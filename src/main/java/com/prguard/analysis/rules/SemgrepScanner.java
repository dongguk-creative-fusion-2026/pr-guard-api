package com.prguard.analysis.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.workspace.Checkout;
import com.prguard.workspace.WorkspaceProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 바뀐 파일을 base · head 에서 Semgrep 으로 한 번 검사해, head 에만 있고 이번 PR 이 추가한 줄에 걸친 결과를 지적으로 만든다
 * (레포에 원래 있던 문제는 이번 PR 의 책임이 아니다). 결과마다 용도({@link SemgrepPurpose})를 붙이고,
 * 보안은 {@link SemgrepAnalyzer}(C) 가, 나머지는 {@link SemgrepQualityAnalyzer}(D) 가 가져간다.
 *
 * <p>규칙: 레지스트리(prguard.semgrep.configs) + PR Guard 마이그레이션 규칙 + 레포 팀 규칙(base 쪽 .semgrep.yml · .semgrep/).
 * 규칙에 fix 가 있으면 수정 제안을 붙인다. 실행 파일이 없으면 건너뛰고 이유를 남긴다. 테스트 코드는 보지 않는다.
 */
@Component
public class SemgrepScanner {

    private static final Logger log = LoggerFactory.getLogger(SemgrepScanner.class);
    private static final Pattern TEST_PATH = Pattern.compile("(?:(?:^|/)(?:test|tests|__tests__|spec|fixtures?)/)|(?:Tests?\\.java$)");
    private static final String BUNDLED = "semgrep/prguard-rules.yml";
    private static final List<String> REPO_RULE_FILES = List.of(".semgrep.yml", ".semgrep.yaml");
    private static final String REPO_RULE_DIR = ".semgrep";
    private static final long MAX_RULE_FILE = 256 * 1024;
    private static final int MAX_SNIPPET_LINES = 8;
    private static final int MAX_MESSAGE = 600;
    private static final int MAX_FIX = 300;
    private static final int MAX_STDERR = 400;

    private final SemgrepProperties props;
    private final ObjectMapper mapper;
    private final WorkspaceProperties workspace;
    private volatile Boolean available;
    private volatile Path bundledRules;

    public SemgrepScanner(SemgrepProperties props, ObjectMapper mapper, WorkspaceProperties workspace) {
        this.props = props;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    /**
     * Semgrep 결과 하나.
     *
     * @param file    head 기준 파일 이름 (base 결과도 head 이름으로 옮겨 둔다)
     * @param snippet 걸린 코드. 라인 번호 대신 이것으로 base · head 의 같은 문제를 맞춘다
     * @param fix     규칙이 제안한 바꿀 코드. 없으면 null
     */
    record Hit(String file, String checkId, int start, int end, String message, String severity, String category,
               String cwe, String snippet, String fix) {

        String anchor() {
            return "semgrep:" + checkId + ":" + normalize(snippet);
        }
    }

    /** 이 리뷰의 Semgrep 지적 전부. 검사기 여럿이 불러도 한 번만 실행한다 */
    public List<Finding> scan(AnalysisContext ctx) {
        return ctx.once("semgrep", () -> {
            try {
                return doScan(ctx);
            } catch (RuntimeException e) {
                log.warn("Semgrep 실패: {}", e.toString());
                ctx.notes().add("Semgrep 실패: " + e.getMessage());
                return List.of();
            }
        });
    }

    private List<Finding> doScan(AnalysisContext ctx) {
        if (!props.enabled() || ctx.checkout().isEmpty()) {
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
        Checkout c = ctx.checkout().get();
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

        List<String> configs = new ArrayList<>(props.configs());
        if (props.bundled()) {
            Path bundled = bundledRules();
            if (bundled != null) {
                configs.add(bundled.toString());
            }
        }
        List<Path> teamFiles = props.repoRules() ? repoRuleFiles(c.baseDir()) : List.of();
        Set<String> teamIds = teamFiles.stream().flatMap(p -> ruleIds(p).stream()).collect(Collectors.toSet());

        JsonNode out;
        if (teamIds.isEmpty()) {
            out = run(configs, all);
        } else {
            List<String> withTeam = new ArrayList<>(configs);
            teamFiles.forEach(p -> withTeam.add(p.toString()));
            try {
                out = run(withTeam, all);
                ctx.notes().add("레포 Semgrep 규칙 " + teamIds.size() + "개를 팀 규칙으로 함께 검사");
            } catch (IllegalStateException e) {
                // 레포 규칙 파일이 잘못되면 Semgrep 전체가 멈춘다. 그 규칙만 빼고 다시 돈다
                ctx.notes().add("레포 Semgrep 규칙을 읽지 못해 빼고 검사함: " + e.getMessage());
                teamIds = Set.of();
                out = run(configs, all);
            }
        }
        logErrors(out, ctx);
        List<Hit> headHits = hits(out, head, SemgrepScanner::snippet);
        List<Hit> baseHits = hits(out, base, SemgrepScanner::snippet);
        Map<String, Set<Integer>> added = targets.stream()
                .collect(Collectors.toMap(ChangedFile::filename, f -> f.patch().addedLines(), (a, b) -> a));
        return findings(headHits, baseHits, added, teamIds);
    }

    /** head 에만 있고 추가된 줄에 걸친 결과 → 지적 */
    static List<Finding> findings(List<Hit> head, List<Hit> base, Map<String, Set<Integer>> added, Set<String> teamIds) {
        Set<String> existing = base.stream().map(h -> h.file() + "|" + h.anchor()).collect(Collectors.toSet());
        Set<String> seen = new HashSet<>();
        List<Finding> result = new ArrayList<>();
        for (Hit h : head) {
            String key = h.file() + "|" + h.anchor();
            if (existing.contains(key) || !touches(h, added.getOrDefault(h.file(), Set.of())) || !seen.add(key)) {
                continue;
            }
            SemgrepPurpose purpose = SemgrepPurpose.of(h.checkId(), h.category(), teamIds);
            Severity severity = severity(h.severity());
            if (purpose == SemgrepPurpose.PRACTICE && severity.compareTo(Severity.MINOR) < 0) {
                severity = Severity.MINOR;
            }
            String fix = h.fix() == null || h.fix().isBlank() ? null : clip(h.fix().strip(), MAX_FIX);
            String evidence = h.checkId() + (h.cwe() == null ? "" : " · " + h.cwe())
                    + (h.snippet().isBlank() ? "" : "\n" + h.snippet())
                    + (fix == null ? "" : "\n→ " + fix);
            String message = clip(h.message(), MAX_MESSAGE) + (fix == null ? "" : "\n\n수정 제안: `" + fix + "`");
            result.add(new Finding("SEMGREP", purpose.category(), severity, h.file(), h.start(),
                    "Semgrep · " + purpose.label() + ": " + shortName(h.checkId()), message, evidence, h.anchor(),
                    Finding.TOOL));
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
                    snippet == null ? "" : lines(snippet, start, end), extra.path("fix").asText(null)));
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

    /** 레포(base) 의 팀 규칙 파일. .semgrep.yml · .semgrep.yaml · .semgrep/ 아래 yml */
    static List<Path> repoRuleFiles(Path root) {
        List<Path> result = new ArrayList<>();
        for (String name : REPO_RULE_FILES) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                result.add(p);
            }
        }
        Path dir = root.resolve(REPO_RULE_DIR);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir, 3)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().matches(".*\\.ya?ml"))
                        .sorted()
                        .forEach(result::add);
            } catch (IOException e) {
                log.debug("레포 Semgrep 규칙 디렉터리를 읽지 못함: {}", e.toString());
            }
        }
        return result.stream().filter(p -> size(p) <= MAX_RULE_FILE).toList();
    }

    /** 규칙 파일의 rules[].id. 읽지 못하면 비어 있다 (팀 규칙에서 뺀다) */
    static Set<String> ruleIds(Path file) {
        try {
            Object doc = new Yaml(new LoaderOptions()).load(Files.readString(file, StandardCharsets.UTF_8));
            Set<String> ids = new LinkedHashSet<>();
            if (doc instanceof Map<?, ?> map && map.get("rules") instanceof List<?> rules) {
                for (Object rule : rules) {
                    if (rule instanceof Map<?, ?> r && r.get("id") instanceof String id && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
            return ids;
        } catch (IOException | RuntimeException e) {
            log.debug("Semgrep 규칙 파일을 읽지 못함 {}: {}", file, e.toString());
            return Set.of();
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 클래스패스의 PR Guard 규칙을 Semgrep 이 읽을 수 있게 파일로 한 번 꺼내 둔다 */
    private Path bundledRules() {
        Path known = bundledRules;
        if (known != null && Files.isRegularFile(known)) {
            return known;
        }
        synchronized (this) {
            if (bundledRules != null && Files.isRegularFile(bundledRules)) {
                return bundledRules;
            }
            try (InputStream in = SemgrepScanner.class.getClassLoader().getResourceAsStream(BUNDLED)) {
                if (in == null) {
                    return null;
                }
                Path file = Files.createTempFile(scratch(), "prguard-rules-", ".yml");
                Files.write(file, in.readAllBytes());
                file.toFile().deleteOnExit();
                bundledRules = file;
                return file;
            } catch (IOException e) {
                log.warn("PR Guard Semgrep 규칙을 꺼내지 못함: {}", e.toString());
                return null;
            }
        }
    }

    private JsonNode run(List<String> configs, List<Path> targets) {
        Path out = null;
        Path err = null;
        try {
            out = Files.createTempFile(scratch(), "semgrep-", ".json");
            err = Files.createTempFile(scratch(), "semgrep-", ".log");
            List<String> cmd = new ArrayList<>(List.of(props.binary(), "scan", "--json", "--quiet", "--metrics=off",
                    "--disable-version-check", "--timeout", "30"));
            for (String config : configs) {
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

    /** 규칙 · 출력 파일을 둘 곳. 누구나 쓸 수 있는 시스템 임시 디렉터리 대신 PR Guard 작업 디렉터리 아래 */
    private Path scratch() throws IOException {
        return Files.createDirectories(workspace.dir().resolve("semgrep"));
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
