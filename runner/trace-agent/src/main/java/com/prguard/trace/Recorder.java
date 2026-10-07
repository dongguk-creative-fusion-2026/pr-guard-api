package com.prguard.trace;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 호출 기록. 함수 이름은 번호로 바꿔 세고, JVM 이 끝날 때 JSON 으로 쓴다.
 *
 * <pre>
 * {"methods": ["com.a.Foo#bar", …], "counts": [3, …],
 *  "edges": [[호출하는 쪽, 불린 쪽, 횟수], …],
 *  "tests": {"테스트 함수 번호": [지나간 함수 번호, …]}}
 * </pre>
 *
 * 테스트 구분: 스레드에서 처음 들어온 프로젝트 함수가 이름이 Test · Tests · IT 로 끝나는 클래스의 것이면 그 함수를 테스트로 본다.
 */
public final class Recorder {

    /** 테스트 하나에 남길 함수 수 상한 (큰 통합 테스트가 결과를 너무 키우지 않게) */
    private static final int MAX_PER_TEST = 2000;

    private static volatile String dir;
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static final Map<Integer, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final Map<Long, LongAdder> EDGES = new ConcurrentHashMap<>();
    private static final Map<Integer, Set<Integer>> TESTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Stack> STACK = ThreadLocal.withInitial(Stack::new);
    private static final ThreadLocal<int[]> CURRENT_TEST = ThreadLocal.withInitial(() -> new int[] {-1});

    private Recorder() {
    }

    /** 부트스트랩 로더에 올라가므로 에이전트(앱 로더)에서 부르려면 public 이어야 한다 */
    public static void init(String directory) {
        dir = directory;
    }

    /** 들어오기 전 스택 깊이를 돌려준다. 나갈 때 그 깊이로 되돌린다 */
    public static int enter(String name) {
        int m = id(name);
        Stack stack = STACK.get();
        int[] test = CURRENT_TEST.get();
        if (stack.size == 0) {
            int hash = name.indexOf('#');
            String type = hash < 0 ? name : name.substring(0, hash);
            int dollar = type.indexOf('$');
            String outer = dollar < 0 ? type : type.substring(0, dollar);
            test[0] = outer.endsWith("Test") || outer.endsWith("Tests") || outer.endsWith("IT") ? m : -1;
        } else {
            long key = ((long) stack.peek() << 32) | (m & 0xffffffffL);
            EDGES.computeIfAbsent(key, k -> new LongAdder()).increment();
        }
        COUNTS.computeIfAbsent(m, k -> new LongAdder()).increment();
        if (test[0] >= 0 && test[0] != m) {
            Set<Integer> seen = TESTS.computeIfAbsent(test[0], k -> ConcurrentHashMap.newKeySet());
            if (seen.size() < MAX_PER_TEST) {
                seen.add(m);
            }
        }
        int depth = stack.size;
        stack.push(m);
        return depth;
    }

    public static void exit(int depth) {
        STACK.get().truncate(depth);
    }

    private static int id(String name) {
        Integer id = IDS.get(name);
        if (id != null) {
            return id;
        }
        synchronized (NAMES) {
            return IDS.computeIfAbsent(name, n -> {
                NAMES.add(n);
                return NAMES.size() - 1;
            });
        }
    }

    public static void dump() {
        if (dir == null || COUNTS.isEmpty()) {
            return;
        }
        Path out = Path.of(dir, "trace-" + ProcessHandle.current().pid() + ".json");
        try {
            Files.createDirectories(out.getParent());
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                List<String> names;
                synchronized (NAMES) {
                    names = new ArrayList<>(NAMES);
                }
                w.write("{\"methods\":[");
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) {
                        w.write(',');
                    }
                    w.write(quote(names.get(i)));
                }
                w.write("],\"counts\":[");
                for (int i = 0; i < names.size(); i++) {
                    if (i > 0) {
                        w.write(',');
                    }
                    LongAdder c = COUNTS.get(i);
                    w.write(Long.toString(c == null ? 0 : c.sum()));
                }
                w.write("],\"edges\":[");
                boolean first = true;
                for (Map.Entry<Long, LongAdder> e : EDGES.entrySet()) {
                    if (!first) {
                        w.write(',');
                    }
                    first = false;
                    long k = e.getKey();
                    w.write("[" + (int) (k >>> 32) + "," + (int) k + "," + e.getValue().sum() + "]");
                }
                w.write("],\"tests\":{");
                first = true;
                for (Map.Entry<Integer, Set<Integer>> e : TESTS.entrySet()) {
                    if (!first) {
                        w.write(',');
                    }
                    first = false;
                    w.write("\"" + e.getKey() + "\":[");
                    boolean f2 = true;
                    for (int m : e.getValue()) {
                        if (!f2) {
                            w.write(',');
                        }
                        f2 = false;
                        w.write(Integer.toString(m));
                    }
                    w.write(']');
                }
                w.write("}}");
            }
        } catch (IOException e) {
            System.err.println("[prguard-trace] 기록 실패: " + e);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 스레드별 호출 스택 (함수 번호) */
    private static final class Stack {
        int[] items = new int[64];
        int size;

        void push(int v) {
            if (size == items.length) {
                items = java.util.Arrays.copyOf(items, size * 2);
            }
            items[size++] = v;
        }

        int peek() {
            return items[size - 1];
        }

        void truncate(int depth) {
            if (depth >= 0 && depth < size) {
                size = depth;
            }
        }
    }
}
