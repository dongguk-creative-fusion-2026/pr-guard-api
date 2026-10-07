package com.prguard.ast;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.ast.MethodAstDiff.Shape;
import com.prguard.ast.MethodAstDiff.Signal;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AstDifferTest {

    @TempDir
    Path base;
    @TempDir
    Path head;

    private Map<String, MethodAstDiff> diff(String before, String after) throws Exception {
        Path file = Path.of("src/main/java/a/PostService.java");
        Files.createDirectories(base.resolve(file).getParent());
        Files.createDirectories(head.resolve(file).getParent());
        Files.writeString(base.resolve(file), before);
        Files.writeString(head.resolve(file), after);
        JavaIndexer indexer = new JavaIndexer();
        RepoIndex b = indexer.index(base);
        RepoIndex h = indexer.index(head);
        return AstDiffer.diff(base, head, b, h, MethodDiff.compute(b, h));
    }

    private static String service(String body) {
        return """
                package a;

                import java.util.Optional;

                class PostService {
                    private PostRepository posts;

                %s
                }
                """.formatted(body);
    }

    @Test
    void throwReplacedByNull_isLogicWithSignal() throws Exception {
        Map<String, MethodAstDiff> d = diff(
                service("""
                            Post getPost(Long id) {
                                return posts.findById(id)
                                        .orElseThrow(() -> new NotFoundException("없음: " + id));
                            }
                        """),
                service("""
                            Post getPost(Long id) {
                                return posts.findById(id).orElse(null);
                            }
                        """));

        MethodAstDiff m = d.values().iterator().next();
        assertThat(m.shape()).isEqualTo(Shape.LOGIC);
        assertThat(m.signals()).extracting(Signal::kind).contains("THROW_TO_NULL");
        assertThat(m.signals().get(0).line()).isEqualTo(9);
        assertThat(m.headSource()).contains("orElse(null)");
    }

    @Test
    void renamedLocalVariable_isRename() throws Exception {
        Map<String, MethodAstDiff> d = diff(
                service("""
                            int count(Long id) {
                                int n = posts.count(id);
                                return n;
                            }
                        """),
                service("""
                            int count(Long id) {
                                int total = posts.count(id);
                                return total;
                            }
                        """));

        assertThat(d.values().iterator().next().shape()).isEqualTo(Shape.RENAME);
    }

    @Test
    void removedNullCheckAndChangedCondition_areSignals() throws Exception {
        Map<String, MethodAstDiff> d = diff(
                service("""
                            void rename(Post p, String title) {
                                if (title == null) {
                                    throw new IllegalArgumentException("title");
                                }
                                if (title.length() > 100) {
                                    return;
                                }
                                p.setTitle(title);
                            }
                        """),
                service("""
                            void rename(Post p, String title) {
                                if (title.length() > 200) {
                                    return;
                                }
                                p.setTitle(title);
                            }
                        """));

        assertThat(d.values().iterator().next().signals()).extracting(Signal::kind)
                .contains("NULL_CHECK_REMOVED", "EXCEPTION_REMOVED", "CONDITION_CHANGED");
    }

    @Test
    void removedAuthAnnotation_isSignal() throws Exception {
        Map<String, MethodAstDiff> d = diff(
                service("""
                            @PreAuthorize("hasRole('ADMIN')")
                            void delete(Long id) {
                                posts.deleteById(id);
                            }
                        """),
                service("""
                            void delete(Long id) {
                                posts.deleteById(id);
                            }
                        """));

        assertThat(d.values().iterator().next().signals()).extracting(Signal::kind).contains("AUTH_REMOVED");
    }
}
