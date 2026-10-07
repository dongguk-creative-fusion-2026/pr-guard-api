package com.prguard.evidence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 미리 써 둔 증거 테스트를 쓴다 (LLM 키 없이 로컬 · 시연).
 * {dir}/{owner}/{repo}/{PR 번호}/ 아래 .java 파일을 같은 상대 경로로 레포에 넣는다.
 * 파일 맨 위 주석 {@code // target: …} · {@code // intent: …} 로 겨냥한 메서드와 설명을 적는다.
 */
public class FixtureEvidenceGenerator implements EvidenceGenerator {

    private final Path dir;

    public FixtureEvidenceGenerator(Path dir) {
        this.dir = dir;
    }

    @Override
    public String name() {
        return "fixture";
    }

    @Override
    public List<EvidenceTest> generate(EvidenceRequest request) {
        Path root = dir.resolve(request.repo()).resolve(Integer.toString(request.number()));
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<EvidenceTest> tests = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = Files.readString(file, StandardCharsets.UTF_8);
                String path = root.relativize(file).toString().replace('\\', '/');
                tests.add(new EvidenceTest(path, className(path), header(code, "target"), header(code, "intent"), code));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tests;
    }

    /** src/test/java/com/a/FooTest.java → com.a.FooTest */
    static String className(String path) {
        String p = path.replaceFirst("^.*?src/test/java/", "");
        return p.substring(0, p.length() - ".java".length()).replace('/', '.');
    }

    private static String header(String code, String key) {
        for (String line : code.lines().limit(10).toList()) {
            String l = line.strip();
            if (l.startsWith("// " + key + ":")) {
                return l.substring(key.length() + 4).strip();
            }
        }
        return "";
    }
}
