package com.prguard.analysis.rules;

import com.prguard.analysis.AnalysisContext;
import com.prguard.analysis.Analyzer;
import com.prguard.analysis.Category;
import com.prguard.analysis.ChangedFile;
import com.prguard.analysis.Finding;
import com.prguard.analysis.Severity;
import com.prguard.index.CallSite;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * D. 구조 변화: 이번 PR 이 새로 만든 파일 사이 의존(호출)이 프로젝트 구조를 거스르는지 base 와 head 인덱스로 비교한다.
 * <ul>
 *   <li>계층 건너뜀: 웹 계층이 서비스를 거치지 않고 저장소를 바로 부름. 프로젝트가 평소 서비스를 거칠 때만 (관례 위반)</li>
 *   <li>역방향 의존: 아래 계층이 위 계층을 부름 (저장소 → 서비스, 서비스 → 웹, 도메인 → 서비스 · 웹)</li>
 *   <li>새 모듈 의존: 서로 의존하지 않던 두 모듈(src 앞 경로) 사이에 의존이 생김</li>
 * </ul>
 * 증거에는 "출발 파일 → 도착 파일" 을 남겨 화면이 두 건물 사이에 다리를 놓는다.
 */
@Component
public class ArchitectureDeltaAnalyzer implements Analyzer {

    /** 한 리뷰에서 남길 최대 지적 수 */
    private static final int MAX_FINDINGS = 5;

    enum Layer {
        DOMAIN, DATA, SERVICE, WEB
    }

    /** 파일 → 파일 의존 하나와 그 예시 호출 */
    record Edge(String from, String to, String example) {
    }

    @Override
    public String id() {
        return "architecture-delta";
    }

    @Override
    public Category category() {
        return Category.RISK;
    }

    @Override
    public List<Finding> analyze(AnalysisContext ctx) {
        // base · head 인덱스가 둘 다 있어야 비교할 수 있다
        if (ctx.headIndex().parsedFiles() == 0 || ctx.baseIndex().parsedFiles() == 0) {
            return List.of();
        }
        Set<String> changed = ctx.files().stream().map(ChangedFile::filename).collect(Collectors.toSet());
        Map<String, Edge> base = edges(ctx.baseIndex());
        Map<String, Edge> head = edges(ctx.headIndex());
        boolean viaService = base.values().stream().anyMatch(e -> layerOf(e.from()) == Layer.WEB && layerOf(e.to()) == Layer.SERVICE);
        boolean bypassUsual = base.values().stream().anyMatch(e -> layerOf(e.from()) == Layer.WEB && layerOf(e.to()) == Layer.DATA);
        Set<String> baseModules = base.values().stream()
                .filter(e -> crossModule(e.from(), e.to()))
                .map(e -> moduleOf(e.from()) + "\n" + moduleOf(e.to()))
                .collect(Collectors.toSet());

        List<Finding> result = new ArrayList<>();
        for (Edge e : head.values()) {
            if (result.size() >= MAX_FINDINGS) {
                break;
            }
            // 이번 PR 이 만든 의존만: base 에 없고, 출발 파일을 이번 PR 이 바꿨다
            if (base.containsKey(key(e.from(), e.to())) || !changed.contains(e.from())) {
                continue;
            }
            Layer from = layerOf(e.from());
            Layer to = layerOf(e.to());
            String evidence = e.from() + " → " + e.to() + " (" + e.example() + ")";
            if (from == Layer.WEB && to == Layer.DATA && viaService && !bypassUsual) {
                result.add(finding("ARCH_LAYER_BYPASS", Severity.MAJOR, e,
                        "계층 건너뜀: " + name(e.from()) + " → " + name(e.to()),
                        name(e.from()) + " 가 서비스를 거치지 않고 저장소를 바로 부릅니다. 이 프로젝트의 다른 웹 계층은 서비스를 거칩니다. "
                                + "검증 · 트랜잭션 · 권한 확인이 서비스에 있다면 이 경로에서는 빠집니다.",
                        evidence));
            } else if (from != null && to != null && reversed(from, to)) {
                result.add(finding("ARCH_REVERSE_DEPENDENCY", Severity.MAJOR, e,
                        "역방향 의존: " + name(e.from()) + " → " + name(e.to()),
                        label(from) + " 계층의 " + name(e.from()) + " 가 위 계층인 " + label(to) + " 의 " + name(e.to())
                                + " 를 부릅니다. 계층이 서로 물려 한쪽을 바꾸면 다른 쪽이 깨지기 쉽습니다.",
                        evidence));
            } else if (crossModule(e.from(), e.to()) && !baseModules.contains(moduleOf(e.from()) + "\n" + moduleOf(e.to()))) {
                result.add(finding("ARCH_NEW_MODULE_DEPENDENCY", Severity.MAJOR, e,
                        "새 모듈 의존: " + moduleOf(e.from()) + " → " + moduleOf(e.to()),
                        moduleOf(e.from()) + " 모듈이 지금까지 의존하지 않던 " + moduleOf(e.to()) + " 모듈의 "
                                + name(e.to()) + " 를 부르기 시작합니다. 서비스 경계가 무너지는 것은 아닌지 확인해 주세요.",
                        evidence));
            }
        }
        return result;
    }

    private static Finding finding(String rule, Severity severity, Edge e, String title, String message, String evidence) {
        return new Finding(rule, Category.RISK, severity, e.from(), null, title, message, evidence,
                "arch:" + rule + ":" + e.from() + ">" + e.to(), Finding.TOOL);
    }

    /** 레포 안으로 연결된 호출을 파일 → 파일 의존으로 모은다 (테스트 · 같은 파일 제외) */
    static Map<String, Edge> edges(RepoIndex index) {
        Map<String, Edge> result = new LinkedHashMap<>();
        for (CallSite c : index.calls()) {
            if (c.resolution() != CallSite.Resolution.RESOLVED || c.calleeIds().isEmpty()) {
                continue;
            }
            Optional<MethodInfo> caller = index.method(c.callerId());
            Optional<MethodInfo> callee = index.method(c.calleeIds().get(0));
            if (caller.isEmpty() || callee.isEmpty() || caller.get().test() || callee.get().test()) {
                continue;
            }
            String from = caller.get().file();
            String to = callee.get().file();
            if (from.equals(to)) {
                continue;
            }
            result.putIfAbsent(key(from, to), new Edge(from, to, shortName(callee.get())));
        }
        return result;
    }

    private static String key(String from, String to) {
        return from + "\n" + to;
    }

    /** 디렉터리 이름이나 클래스 이름 끝으로 계층을 정한다. 모르면 null */
    static Layer layerOf(String path) {
        String file = name(path);
        String[] dirs = path.toLowerCase(Locale.ROOT).split("/");
        for (int i = dirs.length - 2; i >= 0; i--) {
            switch (dirs[i]) {
                case "web", "controller", "controllers", "api", "rest", "endpoint" -> {
                    return Layer.WEB;
                }
                case "service", "services", "application", "usecase" -> {
                    return Layer.SERVICE;
                }
                case "repository", "repositories", "repo", "dao", "persistence" -> {
                    return Layer.DATA;
                }
                case "domain", "model", "entity", "entities" -> {
                    return Layer.DOMAIN;
                }
                default -> {
                }
            }
        }
        if (file.endsWith("Controller") || file.endsWith("Resource")) {
            return Layer.WEB;
        }
        if (file.endsWith("Service")) {
            return Layer.SERVICE;
        }
        if (file.endsWith("Repository") || file.endsWith("Dao")) {
            return Layer.DATA;
        }
        return null;
    }

    /** 아래 계층이 위 계층을 부르는가. 도메인은 서비스 · 웹을 부르면 역방향 */
    private static boolean reversed(Layer from, Layer to) {
        if (from == Layer.DOMAIN) {
            return to == Layer.SERVICE || to == Layer.WEB;
        }
        return to != Layer.DOMAIN && to.ordinal() > from.ordinal();
    }

    /** src 앞까지가 모듈. 없으면 "" */
    static String moduleOf(String path) {
        int i = path.indexOf("/src/");
        return i > 0 ? path.substring(0, i) : "";
    }

    private static boolean crossModule(String from, String to) {
        String a = moduleOf(from);
        String b = moduleOf(to);
        return !a.isEmpty() && !b.isEmpty() && !a.equals(b);
    }

    private static String name(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String label(Layer layer) {
        return switch (layer) {
            case WEB -> "웹";
            case SERVICE -> "서비스";
            case DATA -> "저장소";
            case DOMAIN -> "도메인";
        };
    }

    private static String shortName(MethodInfo m) {
        String type = m.typeFqn().substring(m.typeFqn().lastIndexOf('.') + 1);
        return type + "." + m.name() + "()";
    }
}
