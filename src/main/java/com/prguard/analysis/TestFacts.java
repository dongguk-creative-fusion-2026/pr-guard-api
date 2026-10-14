package com.prguard.analysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 테스트 메서드 하나가 무엇을 검증하는지 (정적). 테스트 신뢰도 검사의 재료.
 *
 * @param test        @Test 류 어노테이션이 붙은 실행되는 테스트인가
 * @param assertions  검증 호출 수 (assert* · verify* · fail · andExpect · then … , 같은 클래스의 검증 보조 메서드 호출 포함)
 * @param negative    실패 경로 검증 수 (assertThrows · assertThatThrownBy · never() · @Test(expected) …)
 * @param trivial     항상 참인 검증 원문 (assertTrue(true), assertEquals(x, x) …)
 * @param swallowed   예외를 삼키는 빈 catch 가 있고 try 안에 fail() 이 없음
 * @param disabled    @Disabled · @Ignore
 * @param calls       테스트 본문의 호출 이름 (검증 대상 추정용)
 */
public record TestFacts(String name, int line, boolean test, int assertions, int negative, List<String> trivial,
                        boolean swallowed, boolean disabled, Set<String> calls) {

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest",
            "TestFactory", "TestTemplate");
    private static final Set<String> ASSERT_NAMES = Set.of("fail", "andExpect", "expectThrows", "then", "should",
            "shouldHaveNoInteractions", "shouldHaveNoMoreInteractions", "expect", "expectMessage");
    private static final Set<String> NEGATIVE_NAMES = Set.of("assertThrows", "assertThrowsExactly", "assertThatThrownBy",
            "assertThatExceptionOfType", "assertThatIllegalArgumentException", "assertThatIllegalStateException",
            "assertThatNullPointerException", "assertThatIOException", "expectThrows", "never");
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    public static boolean isAssertion(String name) {
        return name.startsWith("assert") || name.startsWith("verify") || ASSERT_NAMES.contains(name);
    }

    /** 소스에서 이 이름 · 시작 줄의 메서드 (줄이 맞는 것이 없으면 이름만 같은 첫 메서드) */
    public static Optional<TestFacts> of(String source, String methodName, int line) {
        if (source == null) {
            return Optional.empty();
        }
        Optional<CompilationUnit> cu;
        try {
            cu = PARSER.parse(source).getResult();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (cu.isEmpty()) {
            return Optional.empty();
        }
        List<MethodDeclaration> named = cu.get().findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName)).toList();
        Optional<MethodDeclaration> m = named.stream().filter(d -> begin(d) == line).findFirst()
                .or(() -> named.stream().findFirst());
        return m.map(TestFacts::of);
    }

    /** 클래스에 @Disabled · @Ignore 가 붙은 테스트 클래스 이름들 */
    public static Set<String> disabledClasses(String source) {
        Set<String> out = new LinkedHashSet<>();
        if (source == null) {
            return out;
        }
        try {
            PARSER.parse(source).getResult().ifPresent(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                if (c.getAnnotations().stream().anyMatch(TestFacts::disabling)) {
                    out.add(c.getNameAsString());
                }
            }));
        } catch (RuntimeException e) {
            // 읽지 못하면 없는 것으로 본다
        }
        return out;
    }

    static TestFacts of(MethodDeclaration m) {
        boolean test = m.getAnnotations().stream().anyMatch(a -> TEST_ANNOTATIONS.contains(simple(a)));
        boolean disabled = m.getAnnotations().stream().anyMatch(TestFacts::disabling);
        int assertions = 0;
        int negative = 0;
        // JUnit 4: @Test(expected = X.class)
        for (AnnotationExpr a : m.getAnnotations()) {
            if (simple(a).equals("Test") && a instanceof NormalAnnotationExpr n
                    && n.getPairs().stream().anyMatch(p -> p.getNameAsString().equals("expected"))) {
                assertions++;
                negative++;
            }
        }
        List<String> trivial = new ArrayList<>();
        Set<String> calls = new LinkedHashSet<>();
        Set<String> helpers = assertingHelpers(m);
        for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            calls.add(name);
            boolean helper = helpers.contains(name)
                    && (call.getScope().isEmpty() || call.getScope().get() instanceof ThisExpr);
            if (isAssertion(name) || helper) {
                assertions++;
            }
            if (NEGATIVE_NAMES.contains(name)) {
                negative++;
            }
            trivialText(call).ifPresent(trivial::add);
        }
        boolean swallowed = m.findAll(TryStmt.class).stream().anyMatch(t ->
                t.getCatchClauses().stream().anyMatch(TestFacts::empty)
                        && t.getTryBlock().findAll(MethodCallExpr.class).stream().noneMatch(c -> c.getNameAsString().equals("fail")));
        return new TestFacts(m.getNameAsString(), begin(m), test, assertions, negative, List.copyOf(trivial), swallowed,
                disabled, calls);
    }

    /** 같은 클래스에서 검증 호출을 하는 보조 메서드 이름 (검증을 감싼 helper 를 부르는 테스트도 검증한 것으로 본다) */
    private static Set<String> assertingHelpers(MethodDeclaration m) {
        Set<String> out = new LinkedHashSet<>();
        m.findAncestor(ClassOrInterfaceDeclaration.class).ifPresent(c -> c.getMethods().forEach(other -> {
            if (other != m && other.findAll(MethodCallExpr.class).stream().anyMatch(x -> isAssertion(x.getNameAsString()))) {
                out.add(other.getNameAsString());
            }
        }));
        return out;
    }

    /** 늘 참이라 아무것도 검증하지 않는 검증 */
    private static Optional<String> trivialText(MethodCallExpr call) {
        String name = call.getNameAsString();
        List<Expression> args = call.getArguments();
        boolean trivial = switch (name) {
            case "assertTrue" -> !args.isEmpty() && args.get(0) instanceof BooleanLiteralExpr b && b.getValue();
            case "assertFalse" -> !args.isEmpty() && args.get(0) instanceof BooleanLiteralExpr b && !b.getValue();
            case "assertEquals", "assertSame" -> args.size() >= 2 && args.get(0).toString().equals(args.get(1).toString());
            case "assertNotNull" -> !args.isEmpty() && args.get(0) instanceof ObjectCreationExpr;
            // assertThat(true).isTrue() · assertThat(x).isEqualTo(x)
            case "isTrue" -> scopeArg(call).filter(e -> e instanceof BooleanLiteralExpr b && b.getValue()).isPresent();
            case "isEqualTo", "isSameAs" -> args.size() == 1 && scopeArg(call).map(e -> e.toString().equals(args.get(0).toString())).orElse(false);
            default -> false;
        };
        return trivial ? Optional.of(call.toString().length() > 80 ? call.toString().substring(0, 80) + "…" : call.toString())
                : Optional.empty();
    }

    /** assertThat(X).isTrue() 에서 X */
    private static Optional<Expression> scopeArg(MethodCallExpr call) {
        return call.getScope().filter(s -> s instanceof MethodCallExpr).map(s -> (MethodCallExpr) s)
                .filter(s -> s.getNameAsString().equals("assertThat") && s.getArguments().size() == 1)
                .map(s -> s.getArgument(0));
    }

    private static boolean empty(CatchClause c) {
        return c.getBody().getStatements().isEmpty();
    }

    private static boolean disabling(AnnotationExpr a) {
        return simple(a).equals("Disabled") || simple(a).equals("Ignore");
    }

    /** @org.junit.jupiter.api.Disabled 처럼 패키지까지 적어도 이름만 본다 */
    private static String simple(AnnotationExpr a) {
        return a.getName().getIdentifier();
    }

    private static int begin(Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }
}
