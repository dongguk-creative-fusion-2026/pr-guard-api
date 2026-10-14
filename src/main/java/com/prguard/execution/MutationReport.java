package com.prguard.execution;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * PIT 변이 결과(mutations.xml)를 읽어 바뀐 메서드별로 묶는다.
 * 러너가 보낸 파일은 PR 코드가 만든 것이라 믿을 수 없으므로 외부 엔티티 · DTD 를 막는다.
 */
public final class MutationReport {

    private static final int MAX_MUTANTS = 400;
    private static final int MAX_TEXT = 200;

    private MutationReport() {
    }

    /**
     * 변이 하나.
     *
     * @param status  KILLED(테스트가 잡음) · SURVIVED(지나가는 테스트가 있는데 못 잡음) · NO_COVERAGE(지나가는 테스트 없음) ·
     *                TIMED_OUT · MEMORY_ERROR · RUN_ERROR(잡은 것으로 본다) · NON_VIABLE(무시)
     * @param mutator 짧은 이름 (NegateConditionals …)
     * @param killers 변이를 잡은 테스트 (a.FooTest#method())
     * @param covering 변이를 지나갔지만 못 잡은 테스트 (fullMutationMatrix)
     */
    public record Mutant(String target, String mutatedClass, String method, int line, String mutator,
                         String description, String status, List<String> killers, List<String> covering) {

        public boolean killed() {
            return "KILLED".equals(status) || "TIMED_OUT".equals(status) || "MEMORY_ERROR".equals(status)
                    || "RUN_ERROR".equals(status);
        }

        public boolean survived() {
            return "SURVIVED".equals(status);
        }

        public boolean uncovered() {
            return "NO_COVERAGE".equals(status);
        }
    }

    /** 바뀐 메서드 하나의 변이 결과 */
    public record MethodScore(String id, String file, int line, int total, int killed, int survived, int uncovered,
                              List<Mutant> mutants) {

        /** 테스트가 지나간 변이 중 잡은 비율. 지나간 것이 없으면 -1 */
        public double score() {
            int covered = killed + survived;
            return covered == 0 ? -1 : (double) killed / covered;
        }
    }

    /** 바뀐 메서드 안의 변이만 남긴다 */
    public static List<Mutant> parse(List<InputStream> xmls, MutationPlan plan) {
        List<Mutant> out = new ArrayList<>();
        for (InputStream xml : xmls) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                Document doc = f.newDocumentBuilder().parse(xml);
                NodeList nodes = doc.getElementsByTagName("mutation");
                for (int i = 0; i < nodes.getLength() && out.size() < MAX_MUTANTS; i++) {
                    Element m = (Element) nodes.item(i);
                    String status = m.getAttribute("status");
                    if ("NON_VIABLE".equals(status)) {
                        continue;
                    }
                    String cls = text(m, "mutatedClass");
                    int line = parseInt(text(m, "lineNumber"));
                    MutationPlan.Target target = plan.methods().stream().filter(t -> t.matches(cls, line)).findFirst().orElse(null);
                    if (target == null) {
                        continue;
                    }
                    String mutator = text(m, "mutator");
                    out.add(new Mutant(target.id(), cls, text(m, "mutatedMethod"), line,
                            mutator.substring(mutator.lastIndexOf('.') + 1).replace("Mutator", ""),
                            cut(text(m, "description")), status,
                            testNames(first(m, "killingTests", "killingTest")), testNames(text(m, "succeedingTests"))));
                }
            } catch (Exception e) {
                // 깨진 보고서 하나 때문에 전체를 버리지 않는다
            }
        }
        return out;
    }

    /** 바뀐 메서드별 점수 (계획의 순서대로, 변이가 하나도 없는 메서드는 뺀다) */
    public static List<MethodScore> byMethod(List<Mutant> mutants, MutationPlan plan) {
        Map<String, List<Mutant>> grouped = new LinkedHashMap<>();
        plan.methods().forEach(t -> grouped.put(t.id(), new ArrayList<>()));
        mutants.forEach(m -> grouped.computeIfAbsent(m.target(), k -> new ArrayList<>()).add(m));
        List<MethodScore> out = new ArrayList<>();
        for (MutationPlan.Target t : plan.methods()) {
            List<Mutant> list = grouped.getOrDefault(t.id(), List.of());
            if (list.isEmpty() || out.stream().anyMatch(o -> o.id().equals(t.id()))) {
                continue;
            }
            int killed = (int) list.stream().filter(Mutant::killed).count();
            int survived = (int) list.stream().filter(Mutant::survived).count();
            int uncovered = (int) list.stream().filter(Mutant::uncovered).count();
            out.add(new MethodScore(t.id(), t.file(), t.startLine(), list.size(), killed, survived, uncovered, list));
        }
        return out;
    }

    /**
     * 테스트 클래스별: 잡은 변이 수 · 지나가기만 한 변이 수. 변이를 하나도 못 잡고 지나가기만 한 테스트는 "약함".
     */
    public static Map<String, int[]> byTest(List<Mutant> mutants) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Mutant m : mutants) {
            m.killers().stream().map(TestSelector::topLevelOfTrace).distinct()
                    .forEach(t -> out.computeIfAbsent(t, k -> new int[2])[0]++);
            m.covering().stream().map(TestSelector::topLevelOfTrace).distinct()
                    .forEach(t -> out.computeIfAbsent(t, k -> new int[2])[1]++);
        }
        return out;
    }

    private static final Pattern JUPITER_METHOD = Pattern.compile("\\[(?:method|test-template|test-factory):([^\\]]+)\\]");

    /**
     * PIT 의 테스트 이름들(| 로 구분) → "a.FooTest#x()"
     * JUnit 5: "a.FooTest.[engine:junit-jupiter]/[class:a.FooTest]/[method:x()]", JUnit 4: "a.FooTest.x(a.FooTest)"
     */
    static List<String> testNames(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String one : raw.split("\\|")) {
            String name = one.strip();
            int engine = name.indexOf(".[engine:");
            if (engine > 0) {
                Matcher method = JUPITER_METHOD.matcher(name);
                String last = null;
                while (method.find()) {
                    last = method.group(1);
                }
                name = name.substring(0, engine) + (last == null ? "" : "#" + last);
            } else if (name.contains("(")) {
                int dot = name.lastIndexOf('.', name.indexOf('('));
                name = dot < 0 ? name : name.substring(0, dot) + "#" + name.substring(dot + 1, name.indexOf('(')) + "()";
            }
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    private static String first(Element parent, String... tags) {
        return Arrays.stream(tags).map(t -> text(parent, t)).filter(s -> !s.isBlank()).findFirst().orElse("");
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().strip();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String cut(String s) {
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }
}
