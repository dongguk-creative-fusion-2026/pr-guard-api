package com.prguard.analysis;

import com.prguard.analysis.Grounding.Layer;
import com.prguard.analysis.Grounding.LayerId;
import com.prguard.analysis.Grounding.Mark;
import com.prguard.index.CallSite;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import com.prguard.index.TypeInfo;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 지적 하나하나를 분석의 층(코드 인덱스 · git 이력 · 실행 · AI)에 비춰 본다.
 *
 * <ul>
 *   <li>인덱스: 결정적 검사가 같은 곳을 지적했는가, AI 가 "없다"고 한 이름이 head 에 실제로 있는가,
 *       "빠졌다"고 한 검사가 연결된 코드(바뀐 메서드 · 호출부 · 그 타입을 받는 메서드)에 이미 있는가</li>
 *   <li>이력: 늘 함께 바뀌던 파일이 빠졌는가, 지운 줄을 만든 커밋, 자주 바뀌는 파일인가</li>
 *   <li>실행: 실행 검증이 같은 파일의 동작 변화를 증명했는가, 테스트가 그 메서드를 지나갔는가</li>
 *   <li>AI: AI 리뷰가 같은 곳을 지적했는가</li>
 * </ul>
 * 인덱스와 어긋나는 AI 지적은 {@link Grounding.Status#REFUTED} 가 되어 판정에서 빠진다.
 */
@Component
public class FindingGrounder {

    /** 같은 문제로 보는 라인 거리 */
    private static final int NEAR_LINES = 3;
    /** 이 횟수 이상 바뀐 파일은 "자주 바뀌는 파일" */
    private static final int HOTSPOT_COMMITS = 5;
    private static final int MAX_CONNECTED = 30;
    private static final int MAX_NAMES = 3;
    private static final Pattern PARAMS = Pattern.compile("\\(.*\\)$");

    private final AnalysisProperties props;

    public FindingGrounder(AnalysisProperties props) {
        this.props = props;
    }

    /** @param runtimeEnabled 실행 검증이 켜져 있는가 (꺼져 있으면 실행 층은 "꺼짐"으로 남는다) */
    public List<Finding> ground(List<Finding> findings, AnalysisContext ctx, boolean runtimeEnabled) {
        Sources sources = new Sources(ctx);
        // 같은 내용의 지적이 둘 있어도 따로 본다
        Map<Finding, List<MethodInfo>> linked = new IdentityHashMap<>();
        for (Finding f : findings) {
            linked.put(f, link(f, ctx));
        }
        List<Finding> result = new ArrayList<>();
        for (Finding f : findings) {
            List<Layer> layers = List.of(
                    index(f, ctx, linked, findings, sources),
                    history(f, ctx),
                    runtime(f, linked, findings, runtimeEnabled),
                    ai(f, linked, findings));
            Grounding claim = f.grounding();
            result.add(f.withGrounding(new Grounding(status(f, layers), claim == null ? null : claim.kind(),
                    claim == null ? List.of() : claim.symbols(), layers)));
        }
        return result;
    }

    private static Grounding.Status status(Finding f, List<Layer> layers) {
        Mark index = layers.get(0).mark();
        Mark runtime = layers.get(2).mark();
        if (index == Mark.REFUTED && runtime != Mark.CONFIRMED) {
            return Grounding.Status.REFUTED;
        }
        if (index == Mark.CONFIRMED || runtime == Mark.CONFIRMED || Finding.TOOL.equals(f.source())) {
            return Grounding.Status.CONFIRMED;
        }
        return Grounding.Status.AI_ONLY;
    }

    // ── 지적이 가리키는 코드 ─────────────────────────────────────────

    /** 지적이 가리키는 head 메서드: 라인이 있으면 그 라인을 감싸는 메서드, 없으면 그 파일의 바뀐 메서드. */
    private static List<MethodInfo> link(Finding f, AnalysisContext ctx) {
        if (f.file() == null) {
            return List.of();
        }
        RepoIndex head = ctx.headIndex();
        if (f.line() != null) {
            return head.methodAt(f.file(), f.line()).map(List::of).orElse(List.of());
        }
        return ctx.changedMethods().stream()
                .filter(m -> f.file().equals(m.file()) && m.id() != null)
                .map(m -> head.method(m.id()))
                .flatMap(Optional::stream)
                .limit(5)
                .toList();
    }

    /** 두 지적이 같은 곳을 말하는가: 같은 파일이고, 같은 메서드이거나 라인이 가깝다. */
    private static boolean overlaps(Finding a, Finding b, Map<Finding, List<MethodInfo>> linked) {
        if (a == b || a.file() == null || !a.file().equals(b.file())) {
            return false;
        }
        if (a.line() != null && b.line() != null && Math.abs(a.line() - b.line()) <= NEAR_LINES) {
            return true;
        }
        Set<String> ids = linked.get(a).stream().map(MethodInfo::id).collect(Collectors.toSet());
        return linked.get(b).stream().anyMatch(m -> ids.contains(m.id()));
    }

    private static boolean indexRule(Finding f) {
        String rule = f.ruleId();
        return Finding.TOOL.equals(f.source())
                && (rule.startsWith("AST_") || rule.equals("UNRESOLVED_METHOD") || rule.equals("ARITY_MISMATCH"));
    }

    private static boolean execRule(Finding f) {
        return Finding.TOOL.equals(f.source()) && f.ruleId().startsWith("EXEC_");
    }

    // ── 코드 인덱스 층 ────────────────────────────────────────────────

    private Layer index(Finding f, AnalysisContext ctx, Map<Finding, List<MethodInfo>> linked, List<Finding> all,
                        Sources sources) {
        if (indexRule(f)) {
            return layer(LayerId.INDEX, Mark.CONFIRMED, "정적 검사 " + f.ruleId() + " 가 찾음");
        }
        if (!ctx.indexed()) {
            return layer(LayerId.INDEX, Mark.NONE, "레포 인덱스 없음");
        }
        if (f.file() != null && !f.file().endsWith(".java")) {
            return layer(LayerId.INDEX, Mark.NONE, "Java 인덱스 밖 파일");
        }
        for (Finding other : all) {
            if (indexRule(other) && overlaps(f, other, linked)) {
                return layer(LayerId.INDEX, Mark.CONFIRMED, "정적 검사 " + other.ruleId() + " 도 같은 곳을 지적");
            }
        }
        Grounding claim = f.grounding();
        if (claim != null && !claim.symbols().isEmpty()) {
            Optional<Layer> checked = switch (claim.kind()) {
                case "MISSING_SYMBOL" -> missingSymbol(claim.symbols(), ctx.headIndex());
                case "MISSING_CHECK" -> missingCheck(claim.symbols(), f, linked.get(f), ctx.headIndex(), sources);
                default -> Optional.empty();
            };
            if (checked.isPresent()) {
                return checked.get();
            }
        }
        if (claim != null && "BROKEN_CALLER".equals(claim.kind())) {
            Optional<Layer> callers = brokenCaller(linked.get(f), ctx);
            if (callers.isPresent()) {
                return callers.get();
            }
        }
        return context(linked.get(f), ctx);
    }

    /** "없다"고 한 이름이 head 에 모두 있으면 반박, 레포 타입인데 정말 없으면 확인. */
    private static Optional<Layer> missingSymbol(List<String> symbols, RepoIndex head) {
        List<String> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String symbol : symbols) {
            switch (resolve(symbol, head)) {
                case Resolved r when r.location() != null -> found.add(r.location());
                case Resolved r when r.missing() -> missing.add(symbol);
                default -> {
                    // 레포 밖 타입이라 알 수 없다
                }
            }
        }
        if (!missing.isEmpty()) {
            return Optional.of(layer(LayerId.INDEX, Mark.CONFIRMED, "head 인덱스에 없음: " + names(missing)));
        }
        if (!found.isEmpty() && found.size() == symbols.size()) {
            return Optional.of(layer(LayerId.INDEX, Mark.REFUTED, "head 에 있음: " + names(found)));
        }
        return Optional.empty();
    }

    /** 이름 하나를 head 인덱스에서 찾는다. location 이 있으면 있음, missing 이면 레포 타입인데 없음, 둘 다 아니면 모름. */
    record Resolved(String location, boolean missing) {
        static final Resolved UNKNOWN = new Resolved(null, false);
        static final Resolved MISSING = new Resolved(null, true);
    }

    static Resolved resolve(String symbol, RepoIndex head) {
        String s = PARAMS.matcher(symbol.strip()).replaceAll("");
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        String type;
        String member;
        int hash = s.indexOf('#');
        int dot = s.lastIndexOf('.');
        if (hash >= 0) {
            type = s.substring(0, hash);
            member = s.substring(hash + 1);
        } else if (dot >= 0 && dot < s.length() - 1 && Character.isLowerCase(s.charAt(dot + 1))) {
            type = s.substring(0, dot);
            member = s.substring(dot + 1);
        } else if (!s.isEmpty() && Character.isUpperCase(s.charAt(0)) || dot >= 0) {
            type = s;
            member = null;
        } else {
            type = null;
            member = s;
        }
        if (type == null) {
            // 타입 없이 메서드 이름만: 레포 어딘가에 있으면 있음, 없으면 라이브러리일 수 있어 모름
            return head.methods().values().stream()
                    .filter(m -> m.name().equals(member))
                    .findFirst()
                    .map(m -> new Resolved(location(m), false))
                    .orElse(Resolved.UNKNOWN);
        }
        Optional<TypeInfo> found = findType(type, head);
        if (found.isEmpty()) {
            return Resolved.UNKNOWN;
        }
        TypeInfo t = found.get();
        if (member == null || member.isEmpty()) {
            return new Resolved(t.simpleName() + " (" + fileName(t.file()) + ":" + t.line() + ")", false);
        }
        for (TypeInfo level : head.hierarchy(t.fqn())) {
            if (level.fields().containsKey(member)) {
                return new Resolved(level.simpleName() + "." + member + " (" + fileName(level.file()) + ")", false);
            }
            Optional<MethodInfo> method = head.methods().values().stream()
                    .filter(m -> m.typeFqn().equals(level.fqn()) && m.name().equals(member))
                    .findFirst();
            if (method.isPresent()) {
                return new Resolved(location(method.get()), false);
            }
        }
        return head.hasExternalHierarchy(t.fqn()) ? Resolved.UNKNOWN : Resolved.MISSING;
    }

    private static Optional<TypeInfo> findType(String name, RepoIndex head) {
        TypeInfo exact = head.types().get(name);
        if (exact != null) {
            return Optional.of(exact);
        }
        String simple = name.substring(name.lastIndexOf('.') + 1);
        return head.types().values().stream().filter(t -> t.simpleName().equals(simple)).findFirst();
    }

    /**
     * "빠졌다"고 한 호출 · 어노테이션을 연결된 코드에서 찾는다. 연결된 코드 = 지적이 가리키는 메서드, 그 메서드를 부르는 메서드,
     * 지적 파일의 타입을 파라미터로 받는 메서드 (DTO 의 검증처럼 다른 파일에 붙는 것).
     */
    private static Optional<Layer> missingCheck(List<String> claimed, Finding f, List<MethodInfo> linked,
                                                RepoIndex head, Sources sources) {
        List<String> symbols = checkSymbols(claimed, f, linked, head);
        if (symbols.isEmpty()) {
            return Optional.empty();
        }
        Map<String, MethodInfo> connected = new LinkedHashMap<>();
        linked.forEach(m -> connected.put(m.id(), m));
        for (MethodInfo m : linked) {
            head.callersOf(m.id()).stream()
                    .map(c -> head.method(c.callerId()))
                    .flatMap(Optional::stream)
                    .forEach(c -> connected.putIfAbsent(c.id(), c));
        }
        if (f.file() != null) {
            Set<String> declared = head.typesIn(f.file()).stream().map(TypeInfo::simpleName).collect(Collectors.toSet());
            head.methods().values().stream()
                    .filter(m -> !m.implicit() && !m.test() && !f.file().equals(m.file()))
                    .filter(m -> m.paramTypes().stream().anyMatch(p -> declared.contains(simpleType(p))))
                    .forEach(m -> connected.putIfAbsent(m.id(), m));
        }
        if (connected.isEmpty()) {
            return Optional.empty();
        }
        List<MethodInfo> scope = connected.values().stream().limit(MAX_CONNECTED).toList();
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String symbol : symbols) {
            Optional<MethodInfo> where = scope.stream().filter(m -> has(m, symbol, head, sources)).findFirst();
            if (where.isPresent()) {
                present.add(symbol + " — " + shortId(where.get().id()));
            } else {
                absent.add(symbol);
            }
        }
        if (absent.isEmpty()) {
            return Optional.of(layer(LayerId.INDEX, Mark.REFUTED, "연결된 코드에 이미 있음: " + names(present)));
        }
        if (present.isEmpty()) {
            return Optional.of(layer(LayerId.INDEX, Mark.CONFIRMED, "연결된 메서드 " + scope.size() + "개 어디에도 없음: "
                    + names(absent)));
        }
        return Optional.of(layer(LayerId.INDEX, Mark.RELATED, "일부만 있음: " + names(present) + " · 없음: " + names(absent)));
    }

    /**
     * "빠졌다"고 한 이름 중 검사로 볼 수 있는 것만 남긴다. LLM 은 빠진 호출 대신 지적 위치(그 메서드, 그 파일의 타입,
     * 필드 · record 접근자)를 적기도 하는데, 그런 이름은 당연히 연결된 코드에 있으니 반박의 근거가 될 수 없다.
     * 남는 것: 어노테이션, 그리고 다른 파일에 선언된 메서드 호출.
     */
    static List<String> checkSymbols(List<String> symbols, Finding f, List<MethodInfo> linked, RepoIndex head) {
        Set<String> linkedNames = linked.stream().map(MethodInfo::name).collect(Collectors.toSet());
        List<String> result = new ArrayList<>();
        for (String symbol : symbols) {
            String s = PARAMS.matcher(symbol.strip()).replaceAll("");
            if (s.startsWith("@")) {
                result.add(symbol);
                continue;
            }
            int cut = Math.max(s.lastIndexOf('#'), s.lastIndexOf('.'));
            String member = s.substring(cut + 1);
            String typeName = cut < 0 ? null : s.substring(0, cut);
            if (member.isEmpty() || Character.isUpperCase(member.charAt(0)) || linkedNames.contains(member)) {
                // 타입 이름이거나 지적이 가리키는 메서드 자신
                continue;
            }
            Optional<TypeInfo> type = typeName == null ? Optional.empty() : findType(typeName, head);
            if (type.isPresent()) {
                TypeInfo t = type.get();
                boolean subject = f.file() != null && f.file().equals(t.file());
                boolean field = t.fields().containsKey(member) || head.methods().values().stream()
                        .anyMatch(m -> m.typeFqn().equals(t.fqn()) && m.name().equals(member) && m.implicit());
                if (subject || field) {
                    continue;
                }
            } else if (typeName == null && f.file() != null && head.typesIn(f.file()).stream()
                    .anyMatch(t -> t.fields().containsKey(member))) {
                continue;
            }
            result.add(symbol);
        }
        return result;
    }

    /** 메서드가 이 호출 · 어노테이션을 가지는가. 어노테이션은 파라미터에도 붙으므로 소스로 본다. */
    private static boolean has(MethodInfo m, String symbol, RepoIndex head, Sources sources) {
        String s = PARAMS.matcher(symbol.strip()).replaceAll("");
        if (s.startsWith("@")) {
            String name = s.substring(1);
            String simple = name.substring(name.lastIndexOf('.') + 1);
            return m.hasAnnotation(simple)
                    || Pattern.compile("@" + Pattern.quote(simple) + "\\b").matcher(sources.body(m)).find();
        }
        String name = s.substring(Math.max(s.lastIndexOf('#'), s.lastIndexOf('.')) + 1);
        return head.calls().stream().anyMatch(c -> c.callerId().equals(m.id()) && c.calleeName().equals(name));
    }

    /** 옛 시그니처로 부르는 곳이 있으면 확인, 호출부가 있으면 맥락. */
    private static Optional<Layer> brokenCaller(List<MethodInfo> linked, AnalysisContext ctx) {
        Set<String> ids = linked.stream().map(MethodInfo::id).collect(Collectors.toSet());
        List<ChangedMethod> changed = ctx.changedMethods().stream()
                .filter(m -> m.id() != null && ids.contains(m.id()) && m.signatureChanged())
                .toList();
        for (ChangedMethod m : changed) {
            RepoIndex head = ctx.headIndex();
            // AnalysisPipeline.staleCalls 와 같은 기준: 이름은 맞는데 인자 수가 맞는 head 메서드가 없는 호출
            List<CallSite> stale = head.callsByName(m.typeFqn(), m.name()).stream()
                    .filter(c -> c.argCount() >= 0 && c.calleeIds().stream()
                            .map(head::method)
                            .flatMap(Optional::stream)
                            .noneMatch(h -> h.acceptsArgs(c.argCount())))
                    .toList();
            if (!stale.isEmpty()) {
                return Optional.of(layer(LayerId.INDEX, Mark.CONFIRMED, "옛 시그니처로 부르는 곳 " + stale.size() + "곳: "
                        + names(stale.stream().map(c -> fileName(c.file()) + ":" + c.line()).toList())));
            }
        }
        return Optional.empty();
    }

    /** 지적이 바뀐 메서드에 닿아 있으면 그 메서드와 호출부를 맥락으로 남긴다. */
    private static Layer context(List<MethodInfo> linked, AnalysisContext ctx) {
        if (linked.isEmpty()) {
            return layer(LayerId.INDEX, Mark.NONE, "인덱스에서 연결된 메서드를 찾지 못함");
        }
        Set<String> changed = ctx.changedMethods().stream().map(ChangedMethod::id).collect(Collectors.toSet());
        MethodInfo m = linked.stream().filter(x -> changed.contains(x.id())).findFirst().orElse(linked.get(0));
        List<CallSite> callers = ctx.headIndex().callersOf(m.id());
        String note = (changed.contains(m.id()) ? "바뀐 메서드 " : "메서드 ") + shortId(m.id())
                + (callers.isEmpty() ? " · 호출부 없음"
                : " · 호출부 " + callers.size() + "곳 (" + names(callers.stream().map(c -> shortId(c.callerId())).toList()) + ")");
        return layer(LayerId.INDEX, Mark.RELATED, note);
    }

    // ── git 이력 층 ──────────────────────────────────────────────────

    private Layer history(Finding f, AnalysisContext ctx) {
        if (f.file() == null) {
            return layer(LayerId.HISTORY, Mark.NONE, "파일이 정해지지 않은 지적");
        }
        if (ctx.history().commits().isEmpty()) {
            return layer(LayerId.HISTORY, Mark.NONE, "git 이력 없음");
        }
        Set<String> inPr = ctx.files().stream().map(ChangedFile::filename).collect(Collectors.toSet());
        List<String> notes = new ArrayList<>();
        ctx.history().coChanges(f.file(), props.minCoSupport(), props.minConfidence(), 3).stream()
                .filter(c -> !c.directory() && !inPr.contains(c.partner()))
                .findFirst()
                .ifPresent(c -> notes.add("늘 함께 바뀌던 " + fileName(c.partner()) + " 가 이번 PR 에 없음 (" + c.support()
                        + "/" + c.fileCommits() + ")"));
        ctx.blame().stream()
                .filter(b -> f.file().equals(b.file()))
                .findFirst()
                .ifPresent(b -> notes.add("지운 줄의 원래 커밋 " + shortSha(b.sha()) + " \"" + b.summary() + "\""));
        int commits = ctx.history().fileCommitCount(f.file());
        if (commits >= HOTSPOT_COMMITS) {
            notes.add("자주 바뀌는 파일 (커밋 " + commits + "번)");
        }
        if (notes.isEmpty()) {
            return layer(LayerId.HISTORY, Mark.NONE, commits == 0 ? "이력 없는 새 파일" : "이력에 특이점 없음 (커밋 " + commits + "번)");
        }
        return layer(LayerId.HISTORY, Mark.RELATED, String.join(" · ", notes));
    }

    // ── 실행 · 런타임 층 ─────────────────────────────────────────────

    private static Layer runtime(Finding f, Map<Finding, List<MethodInfo>> linked, List<Finding> all,
                                 boolean enabled) {
        if (execRule(f) && !f.ruleId().equals("EXEC_UNTESTED_CHANGE")) {
            return layer(LayerId.RUNTIME, Mark.CONFIRMED, "실행 검증이 찾음: " + f.title());
        }
        for (Finding other : all) {
            if (execRule(other) && other.ruleId().startsWith("EXEC_BEHAVIOR") && other != f
                    && f.file() != null && f.file().equals(other.file())) {
                return layer(LayerId.RUNTIME, Mark.CONFIRMED, "실행으로 증명: " + other.title());
            }
        }
        Set<String> ids = linked.get(f).stream().map(MethodInfo::id).collect(Collectors.toSet());
        for (Finding other : all) {
            if ("EXEC_UNTESTED_CHANGE".equals(other.ruleId()) && other.evidence() != null
                    && other.evidence().lines().anyMatch(ids::contains)) {
                return layer(LayerId.RUNTIME, Mark.RELATED, "테스트가 이 메서드를 한 번도 지나가지 않음");
            }
        }
        return layer(LayerId.RUNTIME, Mark.NONE, enabled ? "실행 결과와 연결 없음" : "실행 검증 꺼짐");
    }

    // ── AI 층 ───────────────────────────────────────────────────────

    private static Layer ai(Finding f, Map<Finding, List<MethodInfo>> linked, List<Finding> all) {
        if (Finding.LLM.equals(f.source())) {
            Grounding claim = f.grounding();
            return layer(LayerId.AI, Mark.CONFIRMED, "AI 리뷰 지적" + (claim == null ? "" : " (" + kindLabel(claim.kind()) + ")"));
        }
        for (Finding other : all) {
            if (Finding.LLM.equals(other.source()) && overlaps(f, other, linked)) {
                return layer(LayerId.AI, Mark.CONFIRMED, "AI 리뷰도 같은 곳을 지적: " + other.title());
            }
        }
        return layer(LayerId.AI, Mark.NONE, "AI 는 지적하지 않음");
    }

    static String kindLabel(String kind) {
        return switch (kind == null ? "OTHER" : kind) {
            case "MISSING_SYMBOL" -> "없는 이름";
            case "BROKEN_CALLER" -> "깨지는 호출부";
            case "MISSING_CHECK" -> "빠진 검사";
            case "BEHAVIOR_CHANGE" -> "동작 변경";
            case "SECURITY" -> "보안";
            case "DATA" -> "데이터";
            case "INTENT" -> "의도 불일치";
            default -> "기타";
        };
    }

    // ── 도우미 ──────────────────────────────────────────────────────

    private static Layer layer(LayerId id, Mark mark, String note) {
        return new Layer(id, mark, note);
    }

    private static String names(List<String> names) {
        String joined = String.join(", ", names.stream().limit(MAX_NAMES).toList());
        return names.size() > MAX_NAMES ? joined + " 외 " + (names.size() - MAX_NAMES) : joined;
    }

    private static String location(MethodInfo m) {
        return shortId(m.id()) + " (" + fileName(m.file()) + ":" + m.startLine() + ")";
    }

    /** com.a.Post#getUpdatedAt() → Post#getUpdatedAt() */
    static String shortId(String id) {
        int hash = id.indexOf('#');
        String type = hash < 0 ? id : id.substring(0, hash);
        return type.substring(type.lastIndexOf('.') + 1) + (hash < 0 ? "" : id.substring(hash));
    }

    private static String simpleType(String type) {
        String t = type.replaceAll("<.*>", "").replace("...", "").replace("[]", "").strip();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    private static String fileName(String path) {
        return path == null ? "-" : path.substring(path.lastIndexOf('/') + 1);
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    /** head 소스의 메서드 본문 (파일은 한 번만 읽는다). 소스가 없으면 빈 문자열. */
    private static final class Sources {
        private final Path headDir;
        private final Map<String, List<String>> files = new HashMap<>();

        Sources(AnalysisContext ctx) {
            this.headDir = ctx.checkout().map(c -> c.headDir()).orElse(null);
        }

        String body(MethodInfo m) {
            if (headDir == null) {
                return "";
            }
            List<String> lines = files.computeIfAbsent(m.file(), this::read);
            int from = Math.max(0, m.startLine() - 1);
            int to = Math.min(lines.size(), m.endLine());
            return from >= to ? "" : String.join("\n", lines.subList(from, to));
        }

        private List<String> read(String file) {
            try {
                return Files.readAllLines(headDir.resolve(file), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        }
    }
}
