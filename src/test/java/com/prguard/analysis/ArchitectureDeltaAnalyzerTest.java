package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.rules.ArchitectureDeltaAnalyzer;
import com.prguard.diff.Patch;
import com.prguard.index.JavaIndexer;
import com.prguard.index.RepoIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchitectureDeltaAnalyzerTest {

    @TempDir
    Path base;

    @TempDir
    Path head;

    private final JavaIndexer indexer = new JavaIndexer();
    private final ArchitectureDeltaAnalyzer analyzer = new ArchitectureDeltaAnalyzer();

    private static final String REPO = """
            package a.repository;
            public class PostRepository {
                public String find(long id) { return "p"; }
            }""";
    private static final String SERVICE = """
            package a.service;
            import a.repository.PostRepository;
            public class PostService {
                private final PostRepository repo;
                public PostService(PostRepository repo) { this.repo = repo; }
                public String get(long id) { return repo.find(id); }
            }""";
    private static final String CONTROLLER_VIA_SERVICE = """
            package a.web;
            import a.service.PostService;
            public class PostController {
                private final PostService service;
                public PostController(PostService service) { this.service = service; }
                public String show(long id) { return service.get(id); }
            }""";

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<Finding> analyze(List<String> changed) {
        RepoIndex b = indexer.index(base);
        RepoIndex h = indexer.index(head);
        AnalysisContext ctx = new AnalysisContext(null,
                changed.stream().map(f -> new ChangedFile(f, "modified", 1, 0, null, null, Patch.EMPTY)).toList());
        ctx.setIndexes(b, h, List.of());
        return analyzer.analyze(ctx);
    }

    private void writeBoth(String path, String content) throws IOException {
        write(base, path, content);
        write(head, path, content);
    }

    @Test
    void controllerCallingRepositoryDirectlyIsLayerBypass() throws IOException {
        writeBoth("src/main/java/a/repository/PostRepository.java", REPO);
        writeBoth("src/main/java/a/service/PostService.java", SERVICE);
        write(base, "src/main/java/a/web/PostController.java", CONTROLLER_VIA_SERVICE);
        write(head, "src/main/java/a/web/PostController.java", """
                package a.web;
                import a.repository.PostRepository;
                import a.service.PostService;
                public class PostController {
                    private final PostService service;
                    private final PostRepository repo;
                    public PostController(PostService service, PostRepository repo) { this.service = service; this.repo = repo; }
                    public String show(long id) { return service.get(id); }
                    public String raw(long id) { return repo.find(id); }
                }""");

        List<Finding> found = analyze(List.of("src/main/java/a/web/PostController.java"));

        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("ARCH_LAYER_BYPASS");
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.evidence()).startsWith("src/main/java/a/web/PostController.java → src/main/java/a/repository/PostRepository.java");
        });
    }

    @Test
    void bypassIsAllowedWhenProjectAlreadyDoesIt() throws IOException {
        writeBoth("src/main/java/a/repository/PostRepository.java", REPO);
        writeBoth("src/main/java/a/web/OwnerController.java", """
                package a.web;
                import a.repository.PostRepository;
                public class OwnerController {
                    private final PostRepository repo;
                    public OwnerController(PostRepository repo) { this.repo = repo; }
                    public String show(long id) { return repo.find(id); }
                }""");
        write(head, "src/main/java/a/web/PostController.java", """
                package a.web;
                import a.repository.PostRepository;
                public class PostController {
                    private final PostRepository repo;
                    public PostController(PostRepository repo) { this.repo = repo; }
                    public String raw(long id) { return repo.find(id); }
                }""");

        assertThat(analyze(List.of("src/main/java/a/web/PostController.java"))).isEmpty();
    }

    @Test
    void repositoryCallingServiceIsReverseDependency() throws IOException {
        writeBoth("src/main/java/a/service/PostService.java", """
                package a.service;
                public class PostService {
                    public String audit() { return "ok"; }
                }""");
        write(base, "src/main/java/a/repository/PostRepository.java", REPO);
        write(head, "src/main/java/a/repository/PostRepository.java", """
                package a.repository;
                import a.service.PostService;
                public class PostRepository {
                    private final PostService service;
                    public PostRepository(PostService service) { this.service = service; }
                    public String find(long id) { return service.audit(); }
                }""");

        assertThat(analyze(List.of("src/main/java/a/repository/PostRepository.java")))
                .singleElement().satisfies(f -> assertThat(f.ruleId()).isEqualTo("ARCH_REVERSE_DEPENDENCY"));
    }

    @Test
    void newDependencyBetweenModules() throws IOException {
        writeBoth("vets/src/main/java/v/VetService.java", """
                package v;
                public class VetService {
                    public int count() { return 1; }
                }""");
        write(base, "owners/src/main/java/o/OwnerService.java", """
                package o;
                public class OwnerService {
                    public int total() { return 0; }
                }""");
        write(head, "owners/src/main/java/o/OwnerService.java", """
                package o;
                import v.VetService;
                public class OwnerService {
                    private final VetService vets;
                    public OwnerService(VetService vets) { this.vets = vets; }
                    public int total() { return vets.count(); }
                }""");

        assertThat(analyze(List.of("owners/src/main/java/o/OwnerService.java")))
                .singleElement().satisfies(f -> {
                    assertThat(f.ruleId()).isEqualTo("ARCH_NEW_MODULE_DEPENDENCY");
                    assertThat(f.title()).contains("owners → vets");
                });
    }

    @Test
    void existingDependenciesAndUnchangedFilesAreIgnored() throws IOException {
        writeBoth("src/main/java/a/repository/PostRepository.java", REPO);
        writeBoth("src/main/java/a/service/PostService.java", SERVICE);
        writeBoth("src/main/java/a/web/PostController.java", CONTROLLER_VIA_SERVICE);

        assertThat(analyze(List.of("src/main/java/a/web/PostController.java"))).isEmpty();
    }
}
