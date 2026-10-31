package com.prguard.trivy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.analysis.Category;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Trivy JSON v2 → 기존 리뷰 모델. 버전/라인 이동은 기존 문제의 식별자를 바꾸지 않는다. */
@Component
public class TrivyReportParser {
    private final ObjectMapper mapper;

    public TrivyReportParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record Report(List<Finding> findings, int targets) {
        public Report {
            findings = List.copyOf(findings);
        }
    }

    public Report parse(String json, Path source) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject() || root.path("SchemaVersion").asInt() != 2
                    || !"filesystem".equals(root.path("ArtifactType").asText())) {
                throw new IllegalArgumentException("Trivy JSON v2 filesystem report required");
            }
            JsonNode results = root.path("Results");
            if (!results.isMissingNode() && !results.isNull() && !results.isArray()) {
                throw new IllegalArgumentException("Invalid Trivy Results");
            }
            // 같은 결과가 중복 직렬화되어도 한 번만 보고한다. 서로 다른 리소스/위치는 보존한다.
            var findings = new LinkedHashMap<String, Finding>();
            for (JsonNode result : results) {
                String file = relative(source, required(result, "Target"));
                String type = text(result, "Type");
                for (JsonNode v : array(result, "Vulnerabilities")) {
                    String id = required(v, "VulnerabilityID");
                    String pkg = required(v, "PkgName");
                    String version = text(v, "InstalledVersion");
                    String fixed = text(v, "FixedVersion");
                    String level = text(v, "Severity");
                    Finding f = new Finding("TRIVY_VULNERABILITY", Category.SECURITY, severity(level), file,
                            null, id + " · " + pkg,
                            "알려진 취약점이 있는 의존성: " + pkg + " " + version + ". "
                                    + (fixed.isBlank() ? "공개된 수정 버전 없음." : "수정 버전: " + fixed + ".")
                                    + " 실제 호출·악용 가능성은 별도 확인이 필요합니다.",
                            "Trivy " + level + " | " + text(v, "Title") + " | " + text(v, "PrimaryURL"),
                            key(type, id, pkg, text(v, "PkgPath")), Finding.TOOL);
                    findings.putIfAbsent(f.fingerprint(), f);
                }
                for (JsonNode m : array(result, "Misconfigurations")) {
                    if (!"FAIL".equals(text(m, "Status"))) {
                        continue;
                    }
                    String id = required(m, "ID");
                    JsonNode cause = m.path("CauseMetadata");
                    int start = cause.path("StartLine").asInt(0);
                    Integer line = start > 0 ? start : null;
                    String level = text(m, "Severity");
                    Finding f = new Finding("TRIVY_MISCONFIGURATION", Category.SECURITY, severity(level), file,
                            line, id + " · " + text(m, "Title"),
                            text(m, "Message") + (text(m, "Resolution").isBlank() ? ""
                                    : " 해결: " + text(m, "Resolution")),
                            "Trivy " + level + " | " + text(cause, "Resource") + " | " + text(m, "PrimaryURL"),
                            key(type, id, text(m, "Namespace"), text(cause, "Resource")), Finding.TOOL);
                    findings.putIfAbsent(f.fingerprint() + "|" + line, f);
                }
            }
            return new Report(new ArrayList<>(findings.values()), results.size());
        } catch (IOException e) {
            // Jackson 의 예외에는 원문이 들어갈 수 있어 외부 보고서 내용을 로그로 전달하지 않는다.
            throw new IllegalArgumentException("Trivy JSON 파싱 실패");
        }
    }

    private static JsonNode array(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isMissingNode() && !value.isNull() && !value.isArray()) {
            throw new IllegalArgumentException("Invalid Trivy " + name);
        }
        return value;
    }

    private String key(String... parts) throws IOException {
        return mapper.writeValueAsString(parts);
    }

    private static Severity severity(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            // CVSS 심각도만으로 실행 가능한 공격이 증명된 것은 아니므로 자동 BLOCKER 로 올리지 않는다.
            case "CRITICAL", "HIGH" -> Severity.MAJOR;
            default -> Severity.MINOR;
        };
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).asText("");
    }

    private static String required(JsonNode node, String key) {
        String value = text(node, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Trivy report missing " + key);
        }
        return value;
    }

    private static String relative(Path source, String target) {
        Path root = source.toAbsolutePath().normalize();
        Path path = Path.of(target.replace('\\', '/'));
        Path absolute = (path.isAbsolute() ? path : root.resolve(path)).normalize();
        if (!absolute.startsWith(root) || absolute.equals(root)) {
            throw new IllegalArgumentException("Trivy target is outside the source directory");
        }
        return root.relativize(absolute).toString().replace('\\', '/');
    }
}
