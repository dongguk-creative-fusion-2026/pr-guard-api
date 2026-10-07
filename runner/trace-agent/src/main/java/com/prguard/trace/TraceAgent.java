package com.prguard.trace;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * 테스트가 도는 동안 프로젝트 패키지의 실제 함수 호출(누가 누구를 몇 번)과 테스트별로 지나간 함수를 기록한다.
 *
 *   TRACE_PACKAGES  기록할 패키지 (쉼표로 구분, 예: com.example.board)
 *   TRACE_DIR       결과를 쓸 디렉터리 (JVM 마다 trace-{pid}.json)
 *
 * 둘 중 하나라도 없으면 아무것도 하지 않는다. JAVA_TOOL_OPTIONS 로 모든 JVM 에 붙으므로
 * 빌드 도구 JVM 에도 붙지만, 그쪽에는 프로젝트 클래스가 없어 빈 결과만 남는다.
 */
public final class TraceAgent {

    private TraceAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        // 에이전트 오류가 JVM 을 죽이면 테스트도 못 돈다. 무슨 일이 있어도 기록만 포기한다
        try {
            install(inst);
        } catch (Throwable e) {
            System.err.println("[prguard-trace] 호출 기록을 끔: " + e);
        }
    }

    private static void install(Instrumentation inst) {
        String packages = System.getenv("TRACE_PACKAGES");
        String dir = System.getenv("TRACE_DIR");
        if (packages == null || packages.isBlank() || dir == null || dir.isBlank()) {
            return;
        }
        try {
            // 기록기(Recorder)는 어느 클래스 로더에서 불려도 한 벌만 있어야 하므로 부트스트랩에 올린다.
            // jar 전체를 올리면 ByteBuddy 가 두 벌이 되어 충돌하므로 Recorder 클래스만 따로 작은 jar 로 만들어 올린다.
            // Recorder 를 처음 참조하기 전에 해야 한다
            inst.appendToBootstrapClassLoaderSearch(recorderJar());
        } catch (Exception e) {
            System.err.println("[prguard-trace] 부트스트랩 등록 실패: " + e);
            return;
        }
        List<String> prefixes = new ArrayList<>();
        for (String p : packages.split(",")) {
            if (!p.isBlank()) {
                prefixes.add(p.strip() + ".");
            }
        }
        Recorder.init(dir);
        AgentBuilder agent = new AgentBuilder.Default();
        // TRACE_DEBUG 가 있으면 클래스 변환 실패를 stderr 로 보인다
        if (System.getenv("TRACE_DEBUG") != null) {
            agent = agent.with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());
        }
        agent
                .ignore(nameStartsWith("com.prguard.trace.").or(nameStartsWith("net.bytebuddy.")))
                .type(type -> !type.isInterface() && !type.isAnnotation() && matches(type.getName(), prefixes))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(MethodAdvice.class)
                                .on(isMethod().and(not(isAbstract())).and(not(isNative())).and(not(isSynthetic()))))
                        .visit(Advice.to(ConstructorAdvice.class).on(isConstructor())))
                .installOn(inst);
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::dump, "prguard-trace-dump"));
    }

    /** Recorder 와 그 안쪽 클래스만 담은 임시 jar */
    private static JarFile recorderJar() throws java.io.IOException {
        File tmp = File.createTempFile("prguard-trace-recorder", ".jar");
        tmp.deleteOnExit();
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(tmp))) {
            for (String name : new String[] {"com/prguard/trace/Recorder.class", "com/prguard/trace/Recorder$Stack.class"}) {
                try (java.io.InputStream in = TraceAgent.class.getClassLoader().getResourceAsStream(name)) {
                    if (in == null) {
                        throw new java.io.IOException("클래스 없음: " + name);
                    }
                    out.putNextEntry(new java.util.jar.JarEntry(name));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
        return new JarFile(tmp);
    }

    private static boolean matches(String name, List<String> prefixes) {
        // Mockito · Spring CGLIB · Hibernate 가 런타임에 만든 클래스는 코드에 없는 함수라 뺀다
        if (name.contains("$MockitoMock$") || name.contains("$$") || name.contains("$ByteBuddy$")
                || name.contains("$HibernateProxy$")) {
            return false;
        }
        for (String p : prefixes) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 각 메서드 앞뒤에 끼워 넣는 코드 */
    public static final class MethodAdvice {

        private MethodAdvice() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static int enter(@Advice.Origin("#t\\##m") String id) {
            return Recorder.enter(id);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter int depth) {
            Recorder.exit(depth);
        }
    }

    /**
     * 생성자용. 생성자는 예외를 잡는 코드로 감쌀 수 없어 정상 종료 때만 빠져나온다.
     * 예외로 끝나 남은 칸은 바깥 메서드가 끝날 때 들어올 때의 깊이로 되돌리며 지워진다
     */
    public static final class ConstructorAdvice {

        private ConstructorAdvice() {
        }

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static int enter(@Advice.Origin("#t\\##m") String id) {
            return Recorder.enter(id);
        }

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void exit(@Advice.Enter int depth) {
            Recorder.exit(depth);
        }
    }
}
