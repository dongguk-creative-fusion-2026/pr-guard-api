package com.prguard.ast;

import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.actions.model.Action;
import com.github.gumtreediff.actions.model.Update;
import com.github.gumtreediff.gen.javaparser.JavaParserGenerator;
import com.github.gumtreediff.matchers.CompositeMatchers;
import com.github.gumtreediff.matchers.MappingStore;
import com.github.gumtreediff.tree.Tree;
import com.prguard.ast.MethodAstDiff.Edit;
import com.prguard.ast.MethodAstDiff.Shape;
import com.prguard.ast.MethodAstDiff.Signal;
import com.prguard.index.ChangedMethod;
import com.prguard.index.MethodInfo;
import com.prguard.index.RepoIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 바뀐 메서드마다 base · head AST 를 GumTree 로 맞대어 편집 목록(삽입 · 삭제 · 수정 · 이동)을 만들고,
 * 변경 성격(겉모양 · 이름 · 위치 · 로직)과 위험 패턴을 정한다.
 *
 * 메서드 본문 해시로는 "바뀌었다" 까지만 알 수 있다. 이걸로 무엇이 어떻게 바뀌었는지를 안다.
 */
public final class AstDiffer {

    private static final int MAX_METHODS = 40;
    private static final int MAX_EDITS = 60;
    private static final int MAX_SOURCE_LINES = 80;
    private static final int MAX_TEXT = 90;
    private static final Set<String> AUTH_ANNOTATIONS = Set.of("PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed");
    /** 이름만 바뀐 것으로 볼 자리: 지역 변수 · 파라미터 · 그 이름을 쓰는 곳. 호출하는 메서드 · 타입 이름이 바뀌면 동작이 바뀐다 */
    private static final Set<String> RENAMEABLE = Set.of("VariableDeclarator", "Parameter", "NameExpr");
    private static final Set<String> ANNOTATION_TYPES = Set.of("MarkerAnnotationExpr", "SingleMemberAnnotationExpr",
            "NormalAnnotationExpr");

    private AstDiffer() {
    }

    /** 본문 · 어노테이션이 바뀐 메서드만 본다. 실패한 메서드는 빼고 계속한다 */
    public static Map<String, MethodAstDiff> diff(Path baseDir, Path headDir, RepoIndex base, RepoIndex head,
                                                  List<ChangedMethod> changed) {
        Map<String, Source> cache = new HashMap<>();
        Map<String, MethodAstDiff> result = new LinkedHashMap<>();
        for (ChangedMethod m : changed) {
            if (result.size() >= MAX_METHODS) {
                break;
            }
            if (m.kind() != ChangedMethod.Kind.MODIFIED || !(m.bodyChanged() || m.annotationsChanged())) {
                continue;
            }
            Optional<MethodInfo> b = base.method(m.baseId());
            Optional<MethodInfo> h = head.method(m.id());
            if (b.isEmpty() || h.isEmpty()) {
                continue;
            }
            try {
                Source bs = cache.computeIfAbsent("b:" + b.get().file(), k -> Source.load(baseDir.resolve(b.get().file())));
                Source hs = cache.computeIfAbsent("h:" + h.get().file(), k -> Source.load(headDir.resolve(h.get().file())));
                if (bs == null || hs == null) {
                    continue;
                }
                Tree bt = bs.method(b.get());
                Tree ht = hs.method(h.get());
                if (bt == null || ht == null) {
                    continue;
                }
                result.put(m.id(), diffMethod(m, bs, bt, b.get(), hs, ht, h.get()));
            } catch (RuntimeException e) {
                // 파싱이 안 되는 소스 하나 때문에 나머지를 버리지 않는다
            }
        }
        return result;
    }

    static MethodAstDiff diffMethod(ChangedMethod m, Source bs, Tree bt, MethodInfo bm, Source hs, Tree ht, MethodInfo hm) {
        MappingStore mappings = new CompositeMatchers.ClassicGumtree().match(bt, ht);
        EditScript script = new SimplifiedChawatheScriptGenerator().computeActions(mappings);
        List<Edit> edits = new ArrayList<>();
        Map<Edit.Action, Integer> counts = new EnumMap<>(Edit.Action.class);
        List<Signal> signals = new ArrayList<>();
        boolean exceptionGone = false;
        int nullLine = 0;
        int renameOnly = 0;
        int moves = 0;
        for (Action a : script) {
            Tree node = a.getNode();
            Edit.Action kind = kindOf(a.getName());
            counts.merge(kind, 1, Integer::sum);
            String type = node.getType().name;
            int baseLine = 0;
            int headLine = 0;
            String text;
            String newLabel = null;
            switch (kind) {
                case INSERT -> {
                    headLine = hs.line(node.getPos());
                    text = hs.text(node);
                }
                case DELETE -> {
                    baseLine = bs.line(node.getPos());
                    text = bs.text(node);
                }
                default -> {
                    baseLine = bs.line(node.getPos());
                    Tree dst = mappings.getDstForSrc(node);
                    headLine = dst == null ? 0 : hs.line(dst.getPos());
                    text = bs.text(node);
                    if (a instanceof Update u) {
                        newLabel = u.getValue();
                    }
                }
            }
            if (edits.size() < MAX_EDITS) {
                edits.add(new Edit(kind, type, node.getLabel(), newLabel, baseLine, headLine, text));
            }
            if (kind == Edit.Action.UPDATE && "SimpleName".equals(type) && node.getParent() != null
                    && RENAMEABLE.contains(node.getParent().getType().name)) {
                renameOnly++;
            }
            if (kind == Edit.Action.UPDATE && "SimpleName".equals(type) && node.getParent() != null
                    && "MethodCallExpr".equals(node.getParent().getType().name)) {
                signals.add(new Signal("CALL_TARGET_CHANGED", headLine, node.getLabel() + "() → " + newLabel + "()"));
            }
            if (kind == Edit.Action.MOVE) {
                moves++;
            }

            // 위험 패턴
            if (kind == Edit.Action.UPDATE && "SimpleName".equals(type) && "orElseThrow".equals(node.getLabel())
                    && newLabel != null && newLabel.startsWith("orElse")) {
                exceptionGone = true;
            }
            if (kind == Edit.Action.DELETE && (contains(node, "ThrowStmt") || createsException(node))) {
                exceptionGone = true;
            }
            if (kind == Edit.Action.INSERT && "NullLiteralExpr".equals(type) && !isComparison(node.getParent())) {
                nullLine = headLine;
            }
            if (kind == Edit.Action.DELETE && (nullCheck(node) || callsNamed(node, "requireNonNull"))) {
                signals.add(new Signal("NULL_CHECK_REMOVED", -baseLine, "null 검사 삭제: " + bs.text(node)));
            }
            if (kind == Edit.Action.DELETE && ANNOTATION_TYPES.contains(type) && AUTH_ANNOTATIONS.contains(annotationName(node))) {
                signals.add(new Signal("AUTH_REMOVED", -baseLine, "권한 어노테이션 삭제: " + bs.text(node)));
            }
            if (kind == Edit.Action.INSERT && "CatchClause".equals(type) && emptyCatch(node)) {
                signals.add(new Signal("EXCEPTION_SWALLOWED", headLine, "빈 catch: " + hs.text(node)));
            }
        }
        if (exceptionGone && nullLine > 0) {
            signals.add(0, new Signal("THROW_TO_NULL", nullLine, "예외를 던지던 곳이 null 을 돌려준다"));
        } else if (exceptionGone) {
            signals.add(0, new Signal("EXCEPTION_REMOVED", firstHeadLine(edits, hm.startLine()), "예외를 던지던 경로가 사라졌다"));
        }
        signals.addAll(conditionChanges(mappings, bs, hs, bt));

        int total = script.size();
        Shape shape = total == 0 ? Shape.COSMETIC
                : renameOnly == total ? Shape.RENAME
                : moves == total ? Shape.MOVE
                : Shape.LOGIC;
        return new MethodAstDiff(m.id(), hm.file(), shape, counts, edits, signals,
                bs.lines(bm.startLine(), bm.endLine()), hs.lines(hm.startLine(), hm.endLine()), bm.startLine(), hm.startLine());
    }

    /** 짝지어진 if · while · 삼항 연산의 조건식 소스가 달라졌으면 */
    private static List<Signal> conditionChanges(MappingStore mappings, Source bs, Source hs, Tree bt) {
        List<Signal> result = new ArrayList<>();
        for (Tree t : bt.preOrder()) {
            String type = t.getType().name;
            if (!(type.equals("IfStmt") || type.equals("WhileStmt") || type.equals("ConditionalExpr")) || t.getChildren().isEmpty()) {
                continue;
            }
            Tree dst = mappings.getDstForSrc(t);
            if (dst == null || dst.getChildren().isEmpty()) {
                continue;
            }
            String before = bs.text(t.getChild(0));
            String after = hs.text(dst.getChild(0));
            if (!before.equals(after)) {
                result.add(new Signal("CONDITION_CHANGED", hs.line(dst.getPos()), before + " → " + after));
            }
        }
        return result;
    }

    private static Edit.Action kindOf(String name) {
        if (name.startsWith("insert")) {
            return Edit.Action.INSERT;
        }
        if (name.startsWith("delete")) {
            return Edit.Action.DELETE;
        }
        if (name.startsWith("update")) {
            return Edit.Action.UPDATE;
        }
        return Edit.Action.MOVE;
    }

    private static boolean contains(Tree node, String type) {
        for (Tree t : node.preOrder()) {
            if (t.getType().name.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** new XxxException(…) 을 품고 있는가 */
    private static boolean createsException(Tree node) {
        for (Tree t : node.preOrder()) {
            if (t.getType().name.equals("ObjectCreationExpr") && !t.getChildren().isEmpty()) {
                String created = t.getChild(0).getChildren().isEmpty() ? t.getChild(0).getLabel()
                        : t.getChild(0).getChild(t.getChild(0).getChildren().size() - 1).getLabel();
                if (created != null && (created.endsWith("Exception") || created.endsWith("Error"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isComparison(Tree parent) {
        return parent != null && parent.getType().name.equals("BinaryExpr");
    }

    /** x == null · x != null 을 품고 있는가 */
    private static boolean nullCheck(Tree node) {
        for (Tree t : node.preOrder()) {
            if (t.getType().name.equals("BinaryExpr")
                    && t.getChildren().stream().anyMatch(c -> c.getType().name.equals("NullLiteralExpr"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean callsNamed(Tree node, String name) {
        for (Tree t : node.preOrder()) {
            if (t.getType().name.equals("MethodCallExpr")
                    && t.getChildren().stream().anyMatch(c -> c.getType().name.equals("SimpleName") && name.equals(c.getLabel()))) {
                return true;
            }
        }
        return false;
    }

    /** 어노테이션 이름 (org.x.PreAuthorize 처럼 패키지가 붙어 있어도 마지막 이름) */
    private static String annotationName(Tree node) {
        for (Tree t : node.preOrder()) {
            String type = t.getType().name;
            if ((type.equals("Name") || type.equals("SimpleName")) && t.getLabel() != null && !t.getLabel().isEmpty()) {
                String label = t.getLabel();
                return label.substring(label.lastIndexOf('.') + 1);
            }
        }
        return "";
    }

    private static boolean emptyCatch(Tree node) {
        return node.getChildren().stream()
                .filter(c -> c.getType().name.equals("BlockStmt"))
                .anyMatch(c -> c.getChildren().isEmpty());
    }

    private static int firstHeadLine(List<Edit> edits, int fallback) {
        return edits.stream().mapToInt(Edit::headLine).filter(l -> l > 0).findFirst().orElse(fallback);
    }

    /** 파일 하나의 소스와 GumTree 트리 */
    static final class Source {
        final String content;
        final Tree root;
        final int[] lineStarts;

        private Source(String content, Tree root) {
            this.content = content;
            this.root = root;
            List<Integer> starts = new ArrayList<>(List.of(0));
            for (int i = 0; i < content.length(); i++) {
                if (content.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        static Source load(Path file) {
            try {
                return of(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        static Source of(String content) {
            try {
                return new Source(content, new JavaParserGenerator().generateFrom().string(content).getRoot());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 1부터 세는 라인 */
        int line(int pos) {
            int lo = 0;
            int hi = lineStarts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStarts[mid] <= pos) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return lo + 1;
        }

        String text(Tree node) {
            int end = Math.min(content.length(), node.getPos() + node.getLength());
            String s = content.substring(Math.max(0, node.getPos()), Math.max(node.getPos(), end)).replaceAll("\\s+", " ").strip();
            return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
        }

        String lines(int from, int to) {
            String[] all = content.split("\n", -1);
            int start = Math.max(1, from);
            int end = Math.min(all.length, Math.min(to, start + MAX_SOURCE_LINES - 1));
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) {
                sb.append(all[i - 1].replace("\r", ""));
                if (i < end) {
                    sb.append('\n');
                }
            }
            return sb.toString();
        }

        /** 인덱스의 메서드(시작 라인 · 이름)에 해당하는 선언 노드. 같은 라인에 여럿이면 가장 안쪽 */
        Tree method(MethodInfo m) {
            Tree best = null;
            for (Tree t : root.preOrder()) {
                String type = t.getType().name;
                if (!type.equals("MethodDeclaration") && !type.equals("ConstructorDeclaration")) {
                    continue;
                }
                int begin = line(t.getPos());
                int end = line(t.getPos() + Math.max(0, t.getLength() - 1));
                boolean named = m.constructor() || t.getChildren().stream()
                        .anyMatch(c -> c.getType().name.equals("SimpleName") && m.name().equals(c.getLabel()));
                if (named && begin <= m.startLine() && end >= m.startLine() && (best == null || t.getLength() < best.getLength())) {
                    best = t;
                }
            }
            return best;
        }
    }
}
