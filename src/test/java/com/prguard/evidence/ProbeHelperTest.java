package com.prguard.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProbeHelperTest {

    /** 기록할 때 실행마다 달라지는 값(시각)은 지운다 */
    static class Post {
        private final String title;
        private final LocalDateTime createdAt = LocalDateTime.now();
        private final List<String> tags;

        Post(String title, List<String> tags) {
            this.title = title;
            this.tags = tags;
        }
    }

    @Test
    void filesFor_oneHelperPerProbePackage() {
        List<EvidenceTest> files = ProbeHelper.filesFor(List.of(
                new EvidenceTest("src/test/java/a/b/PrGuardProbe1Test.java", "a.b.PrGuardProbe1Test", "t", "", ""),
                new EvidenceTest("src/test/java/a/b/PrGuardProbe2Test.java", "a.b.PrGuardProbe2Test", "t", "", ""),
                new EvidenceTest("src/test/java/a/b/PrGuardEvidence1Test.java", "a.b.PrGuardEvidence1Test", "t", "", "")));

        assertThat(files).extracting(EvidenceTest::path).containsExactly("src/test/java/a/b/PrGuardProbe.java");
        assertThat(files.get(0).code()).startsWith("package a.b;");
    }

    @Test
    void source_compilesAndRendersStably(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("probe/PrGuardProbe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, ProbeHelper.source("probe"), StandardCharsets.UTF_8);
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), src.toString());
        assertThat(code).isZero();

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, getClass().getClassLoader())) {
            Method render = loader.loadClass("probe.PrGuardProbe").getDeclaredMethod("render", Object.class, int.class);
            render.setAccessible(true);
            assertThat(render.invoke(null, null, 0)).isEqualTo("null");
            assertThat(render.invoke(null, Optional.empty(), 0)).isEqualTo("Optional.empty");
            assertThat(render.invoke(null, new Post("a", List.of("x")), 0))
                    .isEqualTo("Post{title=\"a\", createdAt=<LocalDateTime>, tags=[\"x\"] (1개)}");
        }
    }
}
