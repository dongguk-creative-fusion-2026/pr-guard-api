package com.prguard.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaIndexerTest {

    @TempDir
    Path base;

    @TempDir
    Path head;

    private final JavaIndexer indexer = new JavaIndexer();

    @Test
    void index_fieldInjectedCall_resolvesToProjectMethod() throws IOException {
        write(base, "src/main/java/a/Guard.java", """
                package a;
                public class Guard {
                    public void check(String s) {}
                }""");
        write(base, "src/main/java/a/Service.java", """
                package a;
                import java.util.List;
                public class Service {
                    private final Guard guard;
                    public Service(Guard guard) { this.guard = guard; }
                    public int run(String s) {
                        guard.check(s);
                        return List.of(s).size();
                    }
                }""");

        RepoIndex index = indexer.index(base);

        List<CallSite> callers = index.callersOf("a.Guard#check(String)");
        assertThat(callers).singleElement()
                .satisfies(c -> assertThat(c.callerId()).isEqualTo("a.Service#run(String)"));
        assertThat(index.calls()).anyMatch(c -> c.calleeName().equals("of")
                && c.resolution() == CallSite.Resolution.EXTERNAL);
    }

    @Test
    void index_callToMissingMethodOnProjectType_marksMissing() throws IOException {
        write(base, "src/main/java/a/Guard.java", """
                package a;
                public class Guard {
                    public void check() {}
                }""");
        write(base, "src/main/java/a/Service.java", """
                package a;
                public class Service {
                    private Guard guard;
                    void run() { guard.verify(); }
                }""");

        RepoIndex index = indexer.index(base);

        assertThat(index.calls()).anyMatch(c -> c.calleeName().equals("verify")
                && c.resolution() == CallSite.Resolution.MISSING);
    }

    @Test
    void index_repositoryExtendingLibraryInterface_doesNotMarkMissing() throws IOException {
        write(base, "src/main/java/a/PostRepository.java", """
                package a;
                import org.springframework.data.jpa.repository.JpaRepository;
                public interface PostRepository extends JpaRepository<Post, Long> {}""");
        write(base, "src/main/java/a/Post.java", "package a; public class Post {}");
        write(base, "src/main/java/a/Service.java", """
                package a;
                public class Service {
                    private PostRepository posts;
                    Object run() { return posts.findById(1L); }
                }""");

        RepoIndex index = indexer.index(base);

        assertThat(index.calls()).filteredOn(c -> c.calleeName().equals("findById"))
                .allMatch(c -> c.resolution() == CallSite.Resolution.EXTERNAL);
    }

    @Test
    void index_recordAccessor_resolvesImplicitMethod() throws IOException {
        write(base, "src/main/java/a/Req.java", "package a; public record Req(String title) {}");
        write(base, "src/main/java/a/Ctl.java", """
                package a;
                public class Ctl {
                    String run(Req req) { return req.title(); }
                }""");

        RepoIndex index = indexer.index(base);

        assertThat(index.callersOf("a.Req#title()")).hasSize(1);
    }

    @Test
    void index_staticImportedCalls_areNotMarkedMissing() throws IOException {
        write(base, "src/test/java/a/FooTest.java", """
                package a;
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.mockito.Mockito.*;
                class FooTest {
                    void t() {
                        assertThat(1).isEqualTo(1);
                        when(null);
                        helper();
                    }
                    private void helper() {}
                }""");

        RepoIndex index = indexer.index(base);

        assertThat(index.calls()).noneMatch(c -> c.resolution() == CallSite.Resolution.MISSING);
        assertThat(index.callersOf("a.FooTest#helper()")).hasSize(1);
    }

    @Test
    void index_constructorCallInFieldInitializer_isCollected() throws IOException {
        write(base, "src/main/java/a/Svc.java", """
                package a;
                public class Svc {
                    public Svc(String a, String b, String c) {}
                }""");
        write(base, "src/test/java/a/SvcTest.java", """
                package a;
                class SvcTest {
                    private final Svc svc = new Svc("a", "b");
                }""");

        RepoIndex index = indexer.index(base);

        assertThat(index.calls()).filteredOn(c -> c.calleeName().equals("<init>") && c.file().endsWith("SvcTest.java"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.callerId()).isEqualTo("a.SvcTest#<field-init>");
                    assertThat(c.argCount()).isEqualTo(2);
                    assertThat(c.calleeIds()).containsExactly("a.Svc#<init>(String,String,String)");
                });
    }

    @Test
    void methodDiff_signatureAndBodyChanges_areClassified() throws IOException {
        write(base, "src/main/java/a/S.java", """
                package a;
                public class S {
                    public String get(Long id) { return "x"; }
                    public void keep() { int a = 1; }
                    public void gone() {}
                }""");
        write(head, "src/main/java/a/S.java", """
                package a;
                public class S {
                    public String get(Long id, boolean lock) { return "x"; }
                    // 주석만 추가
                    public void keep() {
                        int a = 1;
                    }
                    public void added() {}
                }""");

        List<ChangedMethod> changed = MethodDiff.compute(indexer.index(base), indexer.index(head));

        assertThat(changed).extracting(ChangedMethod::kind, ChangedMethod::name, ChangedMethod::signatureChanged)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ChangedMethod.Kind.MODIFIED, "get", true),
                        org.assertj.core.groups.Tuple.tuple(ChangedMethod.Kind.REMOVED, "gone", false),
                        org.assertj.core.groups.Tuple.tuple(ChangedMethod.Kind.ADDED, "added", false));
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
