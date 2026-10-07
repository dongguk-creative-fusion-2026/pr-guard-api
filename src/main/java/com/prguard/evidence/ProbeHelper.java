package com.prguard.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관측 테스트가 쓰는 기록기 PrGuardProbe 소스. 관측 테스트와 같은 패키지에 하나씩 넣는다.
 *
 * {@code PrGuardProbe.record("getPost(999) 없는 글", () -> service.getPost(999L))} 는 결과(반환값이나 던진 예외)를
 * 사람이 읽는 문자열로 바꿔 PRGUARD_PROBE_DIR/probe.jsonl 에 한 줄씩 쓴다.
 * base 와 head 에서 같은 입력의 결과를 맞대야 하므로 실행마다 달라지는 값(객체 해시 · 시각)은 쓰지 않는다:
 * 객체는 필드를 펼치고, 날짜 · 시각은 타입 이름만 남긴다.
 */
public final class ProbeHelper {

    private ProbeHelper() {
    }

    /** 관측 테스트가 있는 패키지 디렉터리마다 기록기 파일 하나 */
    public static List<EvidenceTest> filesFor(List<EvidenceTest> tests) {
        Map<String, EvidenceTest> byDir = new LinkedHashMap<>();
        for (EvidenceTest t : tests) {
            if (!t.probe()) {
                continue;
            }
            String dir = t.path().contains("/") ? t.path().substring(0, t.path().lastIndexOf('/') + 1) : "";
            String pkg = t.className().contains(".") ? t.className().substring(0, t.className().lastIndexOf('.')) : "";
            byDir.computeIfAbsent(dir, d -> new EvidenceTest(d + "PrGuardProbe.java",
                    (pkg.isEmpty() ? "" : pkg + ".") + "PrGuardProbe", null, "관측 기록기", source(pkg)));
        }
        return new ArrayList<>(byDir.values());
    }

    static String source(String pkg) {
        return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + """
                import java.io.IOException;
                import java.lang.reflect.Field;
                import java.lang.reflect.Modifier;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardOpenOption;
                import java.util.Collection;
                import java.util.Map;
                import java.util.Optional;
                import java.util.concurrent.Callable;

                /** PR Guard 동작 diff 기록기. 결과를 PRGUARD_PROBE_DIR/probe.jsonl 에 쓴다 */
                final class PrGuardProbe {

                    private static final int MAX_ITEMS = 10;
                    private static final int MAX_DEPTH = 2;

                    private PrGuardProbe() {
                    }

                    static void record(String label, Callable<?> call) {
                        String out;
                        try {
                            out = render(call.call(), 0);
                        } catch (Throwable t) {
                            out = "throws " + t.getClass().getSimpleName();
                        }
                        String probe = StackWalker.getInstance().walk(s -> s.skip(1).findFirst()
                                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)).orElse(""));
                        write("{\\"probe\\":" + quote(probe) + ",\\"label\\":" + quote(label) + ",\\"out\\":" + quote(out) + "}\\n");
                    }

                    static String render(Object v, int depth) {
                        if (v == null) {
                            return "null";
                        }
                        if (v instanceof CharSequence s) {
                            return "\\"" + s + "\\"";
                        }
                        if (v instanceof Number || v instanceof Boolean || v instanceof Character || v instanceof Enum<?>) {
                            return String.valueOf(v);
                        }
                        if (v instanceof Optional<?> o) {
                            return o.map(x -> "Optional[" + render(x, depth) + "]").orElse("Optional.empty");
                        }
                        if (v instanceof Collection<?> c) {
                            StringBuilder sb = new StringBuilder("[");
                            int i = 0;
                            for (Object x : c) {
                                if (i++ >= MAX_ITEMS) {
                                    sb.append(", …");
                                    break;
                                }
                                sb.append(i > 1 ? ", " : "").append(render(x, depth + 1));
                            }
                            return sb.append("] (").append(c.size()).append("개)").toString();
                        }
                        if (v instanceof Map<?, ?> m) {
                            return "Map(" + m.size() + "개)";
                        }
                        Class<?> type = v.getClass();
                        String name = type.getName();
                        // 시각 · 난수처럼 실행마다 달라지는 값은 타입만 남긴다
                        if (name.startsWith("java.time.") || name.startsWith("java.util.Date") || name.equals("java.util.UUID")) {
                            return "<" + type.getSimpleName() + ">";
                        }
                        if (type.isArray()) {
                            return type.getComponentType().getSimpleName() + "[" + java.lang.reflect.Array.getLength(v) + "]";
                        }
                        if (name.startsWith("java.") || depth >= MAX_DEPTH) {
                            return type.getSimpleName();
                        }
                        StringBuilder sb = new StringBuilder(type.getSimpleName()).append('{');
                        boolean first = true;
                        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                            for (Field f : c.getDeclaredFields()) {
                                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                                    continue;
                                }
                                Object fv;
                                try {
                                    f.setAccessible(true);
                                    fv = f.get(v);
                                } catch (RuntimeException | IllegalAccessException e) {
                                    continue;
                                }
                                sb.append(first ? "" : ", ").append(f.getName()).append('=').append(render(fv, depth + 1));
                                first = false;
                            }
                        }
                        return sb.append('}').toString();
                    }

                    private static synchronized void write(String line) {
                        String dir = System.getenv("PRGUARD_PROBE_DIR");
                        Path file = Path.of(dir == null || dir.isBlank() ? "build/prguard-probe" : dir, "probe.jsonl");
                        try {
                            Files.createDirectories(file.getParent());
                            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        } catch (IOException e) {
                            System.err.println("[prguard-probe] 기록 실패: " + e);
                        }
                    }

                    private static String quote(String s) {
                        StringBuilder sb = new StringBuilder("\\"");
                        for (char ch : s.toCharArray()) {
                            if (ch == '"' || ch == '\\\\') {
                                sb.append('\\\\').append(ch);
                            } else if (ch < 0x20) {
                                sb.append(String.format("\\\\u%04x", (int) ch));
                            } else {
                                sb.append(ch);
                            }
                        }
                        return sb.append('"').toString();
                    }
                }
                """;
    }
}
