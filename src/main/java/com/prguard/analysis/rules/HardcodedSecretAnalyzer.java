package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * C. 보안: 이번 PR 이 추가한 줄에 비밀값을 그대로 적음.
 * 알려진 키 형식(AWS · GitHub · OpenAI · Slack · Google · 개인키)은 BLOCKER,
 * 이름이 비밀값(SECRET · TOKEN · PASSWORD · API_KEY …)인 상수 · 설정에 문자열을 직접 넣은 것은 MAJOR.
 * 테스트 코드와 ${...} 같은 외부 주입은 보지 않는다.
 */
@Component
public class HardcodedSecretAnalyzer implements Analyzer {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
    private static final Pattern TEST_PATH = Pattern.compile("(^|/)(test|tests|__tests__|spec|fixtures?)/|Tests?\\.java$");

    /** 형식만 봐도 실제 키인 것 */
    private static final List<KeyFormat> KEY_FORMATS = List.of(
            new KeyFormat("AWS 액세스 키", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new KeyFormat("GitHub 토큰", Pattern.compile("\\b(ghp|gho|ghs|ghu)_[A-Za-z0-9]{36}\\b|\\bgithub_pat_[A-Za-z0-9_]{40,}")),
            new KeyFormat("OpenAI API 키", Pattern.compile("\\bsk-(proj-)?[A-Za-z0-9_-]{20,}")),
            new KeyFormat("Slack 토큰", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}")),
            new KeyFormat("Google API 키", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b")),
            new KeyFormat("개인키", Pattern.compile("-----BEGIN ([A-Z]+ )?PRIVATE KEY-----")));

    /** 코드: 비밀값 이름 = "문자열" */
    private static final Pattern NAMED_LITERAL = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_]*(?:secret|token|password|passwd|pwd|api_?key|private_?key|credential)[A-Za-z0-9_]*)"
                    + "\\s*[:=]\\s*\"([^\"\\s]{8,})\"");
    /** 설정 파일: 비밀값 키: 값 */
    private static final Pattern NAMED_SETTING = Pattern.compile(
            "(?i)^\\s*([A-Za-z0-9_.-]*(?:secret|token|password|api-?key|private-?key|credential)[A-Za-z0-9_.-]*)\\s*[:=]\\s*\"?([^\"\\s#]{8,})");
    private static final Pattern SETTING_FILE = Pattern.compile("\\.(ya?ml|properties|env)$|(^|/)\\.env(\\.|$)");

    private record KeyFormat(String label, Pattern pattern) {
    }

    /** 추가된 줄 하나 (head 기준 번호와 내용) */
    record AddedLine(int line, String text) {
    }

    @Override
    public String id() {
        return "hardcoded-secret";
    }

    @Override
    public Category category() {
        return Category.SECURITY;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        List<Finding> result = new ArrayList<>();
        for (ChangedFile file : ctx.files()) {
            if (file.removed() || file.patchText() == null || TEST_PATH.matcher(file.filename()).find()) {
                continue;
            }
            boolean setting = SETTING_FILE.matcher(file.filename()).find();
            for (AddedLine added : addedLines(file.patchText())) {
                Finding finding = check(file.filename(), added, setting);
                if (finding != null) {
                    result.add(finding);
                }
            }
        }
        return result;
    }

    private static Finding check(String file, AddedLine added, boolean setting) {
        String text = added.text();
        for (KeyFormat format : KEY_FORMATS) {
            Matcher m = format.pattern().matcher(text);
            if (m.find()) {
                return new Finding("HARDCODED_SECRET", Category.SECURITY, Severity.BLOCKER, file, added.line(),
                        format.label() + "가 코드에 그대로 들어감",
                        format.label() + " 형식의 값이 저장소에 커밋됩니다. 키를 폐기 · 재발급하고 환경 변수나 비밀 저장소에서 읽어 오세요.",
                        mask(text, m.group()), "hardcoded-secret:" + file + ":" + format.label(), Finding.TOOL);
            }
        }
        Matcher m = (setting ? NAMED_SETTING : NAMED_LITERAL).matcher(text);
        if (m.find() && looksSecret(m.group(2))) {
            String name = m.group(1);
            return new Finding("HARDCODED_SECRET", Category.SECURITY, Severity.MAJOR, file, added.line(),
                    "비밀값이 하드코딩됨: " + name,
                    name + " 에 값을 직접 적었습니다. 저장소를 읽을 수 있는 누구나 이 값을 알게 됩니다. 환경 변수나 비밀 저장소에서 읽어 오세요.",
                    mask(text, m.group(2)), "hardcoded-secret:" + file + ":" + name, Finding.TOOL);
        }
        return null;
    }

    /** 자리표시 · 외부 주입 · 헤더 이름처럼 비밀값이 아닌 것을 거른다: 숫자가 섞였거나 충분히 긴 값만 */
    static boolean looksSecret(String value) {
        String lower = value.toLowerCase();
        if (value.startsWith("${") || value.startsWith("<") || lower.contains("changeme") || lower.contains("example")
                || lower.contains("placeholder") || lower.contains("your")) {
            return false;
        }
        boolean digit = value.chars().anyMatch(Character::isDigit);
        return digit || value.length() >= 20;
    }

    /** 증거에 값 전체를 남기지 않는다: 앞 4자만 */
    private static String mask(String line, String secret) {
        String shown = secret.length() <= 4 ? secret : secret.substring(0, 4) + "…";
        return line.strip().replace(secret, shown);
    }

    /** unified diff 에서 추가된 줄과 head 기준 번호 */
    static List<AddedLine> addedLines(String patch) {
        List<AddedLine> result = new ArrayList<>();
        int line = 0;
        for (String raw : patch.split("\n", -1)) {
            Matcher hunk = HUNK.matcher(raw);
            if (hunk.find()) {
                line = Integer.parseInt(hunk.group(1));
            } else if (raw.startsWith("+") && !raw.startsWith("+++")) {
                result.add(new AddedLine(line++, raw.substring(1)));
            } else if (raw.startsWith(" ")) {
                line++;
            }
        }
        return result;
    }
}
