package com.prguard.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.index.ChangedMethod;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** demo-petclinic PR #1 모양: OwnerController#processFindForm(GET /owners) 이 바뀜 */
class HttpProbePlannerTest {

    @TempDir
    Path base;

    @TempDir
    Path head;

    @TempDir
    Path out;

    private static final String APP = """
            package org.example.petclinic;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            @SpringBootApplication
            public class PetClinicApplication {
            }""";
    private static final String CONTROLLER = """
            package org.example.petclinic.owner;
            import org.springframework.stereotype.Controller;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            @Controller
            class OwnerController {
                @GetMapping("/owners")
                public String processFindForm(String lastName) {
                    return "owners/ownersList";
                }
                @GetMapping({"/owners/{ownerId}", "/owner/{ownerId}"})
                public String showOwner(int ownerId) {
                    return "owners/ownerDetails";
                }
                @PostMapping("/owners/new")
                public String create() {
                    return "redirect:/owners/1";
                }
            }""";
    private static final String TESTS = """
            package org.example.petclinic.owner;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
            import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
            class OwnerControllerTests {
                void a() throws Exception { mockMvc.perform(get("/owners?page=1")); }
                void b() throws Exception { mockMvc.perform(get("/owners").param("page", Integer.toString(1)).param("lastName", "Franklin")); }
                void c() throws Exception { mockMvc.perform(get("/owners").param("lastName", "Unknown Surname")); }
                void d() throws Exception { mockMvc.perform(get("/owners/{ownerId}", 1)); }
                void e() throws Exception { mockMvc.perform(post("/owners/new").param("firstName", "Joe")); }
            }""";

    @BeforeEach
    void project() throws IOException {
        for (Path root : List.of(base, head)) {
            write(root, "src/main/java/org/example/petclinic/PetClinicApplication.java", APP);
            write(root, "src/test/java/org/example/petclinic/owner/OwnerControllerTests.java", TESTS);
        }
        write(base, "src/main/java/org/example/petclinic/owner/OwnerController.java", CONTROLLER);
        // PR: 검색 결과 처리를 바꾸고, 상세 화면 · 등록도 손댐
        write(head, "src/main/java/org/example/petclinic/owner/OwnerController.java", CONTROLLER
                .replace("return \"owners/ownersList\";", "return lastName == null ? \"owners/findOwners\" : \"owners/ownersList\";")
                .replace("return \"owners/ownerDetails\";", "return \"owners/ownerDetails2\";")
                .replace("return \"redirect:/owners/1\";", "return \"redirect:/owners/2\";"));
    }

    private List<EvidenceTest> plan(boolean boot) {
        JavaIndexer indexer = new JavaIndexer();
        RepoIndex b = indexer.index(base);
        RepoIndex h = indexer.index(head);
        List<ChangedMethod> changed = MethodDiff.compute(b, h);
        return HttpProbePlanner.plan(changed, h, head, boot);
    }

    @Test
    void changedGetHandlers_getRequestsHarvestedFromExistingTests() {
        List<EvidenceTest> probes = plan(true);

        // POST 핸들러는 상태를 바꿀 수 있어 보내지 않는다
        assertThat(probes).hasSize(2);
        EvidenceTest find = probes.stream().filter(p -> p.target().contains("processFindForm")).findFirst().orElseThrow();
        assertThat(find.className()).isEqualTo("org.example.petclinic.PrGuardProbeHttp1Test");
        assertThat(find.path()).isEqualTo("src/test/java/org/example/petclinic/PrGuardProbeHttp1Test.java");
        assertThat(find.probe()).isTrue();
        // 기존 테스트의 요청 그대로 (상수가 아닌 page 값은 뺀다)
        assertThat(find.code())
                .contains("call(mvc, \"GET /owners?page=1\", \"/owners\", \"page\", \"1\");")
                .contains("call(mvc, \"GET /owners?lastName=Franklin\", \"/owners\", \"lastName\", \"Franklin\");")
                .contains("call(mvc, \"GET /owners?lastName=Unknown Surname\"");

        EvidenceTest show = probes.stream().filter(p -> p.target().contains("showOwner")).findFirst().orElseThrow();
        assertThat(show.code()).contains("call(mvc, \"GET /owners/1\", \"/owners/1\");");
    }

    @Test
    void withoutSpringBootTest_noProbes() {
        assertThat(plan(false)).isEmpty();
    }

    @Test
    void bootTestStarter_detectedIncludingBoot4ModularStarters() throws IOException {
        Files.writeString(out.resolve("build.gradle"), "testImplementation 'org.springframework.boot:spring-boot-starter-test'");
        assertThat(EvidenceService.testLibraries(out)).contains("spring-boot-test", "mockito", "assertj");
        Files.writeString(out.resolve("build.gradle"),
                "testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'");
        assertThat(EvidenceService.testLibraries(out)).contains("spring-boot-test");
        Files.writeString(out.resolve("build.gradle"), "implementation 'org.springframework.boot:spring-boot-starter-web'");
        assertThat(EvidenceService.testLibraries(out)).doesNotContain("spring-boot-test");
    }

    @Test
    void mappingPaths_valuePathArraysAndClassPrefix() {
        assertThat(HttpProbePlanner.paths("@GetMapping(\"/a\")")).containsExactly("/a");
        assertThat(HttpProbePlanner.paths("@GetMapping(value = {\"/a\", \"/b\"}, produces = \"text/html\")")).containsExactly("/a", "/b");
        assertThat(HttpProbePlanner.paths("@RequestMapping(path = \"/v1\")")).containsExactly("/v1");
        assertThat(HttpProbePlanner.paths("@GetMapping")).isEmpty();
    }

    /** 만든 관측 테스트가 실제로 컴파일되는지 (Spring · JUnit 클래스패스로) */
    @Test
    void generatedProbe_compiles() throws IOException {
        EvidenceTest probe = plan(true).get(0);
        Path src = out.resolve(probe.path());
        Files.createDirectories(src.getParent());
        Files.writeString(src, probe.code());

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter errors = new StringWriter();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjects(src.toFile());
            boolean ok = compiler.getTask(errors, files, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", out.resolve("classes").toString(), "-proc:none"),
                    null, units).call();
            assertThat(ok).as(errors.toString()).isTrue();
        }
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path p = root.resolve(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }
}
