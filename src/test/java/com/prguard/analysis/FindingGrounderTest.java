package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.Grounding.LayerId;
import com.prguard.analysis.Grounding.Mark;
import com.prguard.analysis.Grounding.Status;
import com.prguard.diff.Patch;
import com.prguard.github.RepoRef;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindingGrounderTest {

    private static final String POST = "src/main/java/b/Post.java";
    private static final String REQUEST = "src/main/java/b/PostRequest.java";
    private static final String CONTROLLER = "src/main/java/b/PostController.java";
    private static final String SERVICE = "src/main/java/b/PostService.java";

    @TempDir
    Path base;

    @TempDir
    Path head;

    private final FindingGrounder grounder = new FindingGrounder(new AnalysisProperties(500, 2, 0.5, 6000));
    private final VerdictPolicy verdicts = new VerdictPolicy(1);

    @Test
    void ground_aiClaimsMissingMethodThatHeadHas_refutesAndVerdictIgnoresIt() throws IOException {
        // sandbox #22: "Post.getUpdatedAt() 이 없어 컴파일 실패" — head 에는 있다
        writeBoth(POST, """
                package b;
                public class Post {
                    private String title;
                    private java.time.LocalDateTime updatedAt;
                    public String getTitle() { return title; }
                    public java.time.LocalDateTime getUpdatedAt() { return updatedAt; }
                }""");
        AnalysisContext ctx = context(List.of(POST));
        Finding claim = llm(Severity.BLOCKER, POST, null, "MISSING_SYMBOL", "Post#getUpdatedAt()");

        Finding grounded = grounder.ground(List.of(claim), ctx, false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.REFUTED);
        assertThat(grounded.grounding().mark(LayerId.INDEX)).isEqualTo(Mark.REFUTED);
        assertThat(note(grounded, LayerId.INDEX)).contains("Post#getUpdatedAt()").contains("Post.java:6");
        assertThat(verdicts.decide(List.of(grounded))).isEqualTo(Verdict.MERGEABLE);
    }

    @Test
    void ground_aiClaimsMissingMethodOnRepoType_confirms() throws IOException {
        writeBoth(POST, """
                package b;
                public class Post {
                    public String getTitle() { return null; }
                }""");
        Finding claim = llm(Severity.BLOCKER, POST, null, "MISSING_SYMBOL", "Post.getCategory");

        Finding grounded = grounder.ground(List.of(claim), context(List.of(POST)), false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.CONFIRMED);
        assertThat(note(grounded, LayerId.INDEX)).contains("head 인덱스에 없음").contains("Post.getCategory");
    }

    @Test
    void ground_aiClaimsMissingValidationThatCallerHasOnParameter_showsWhereItIsButKeepsFinding() throws IOException {
        // sandbox #20: DTO 에 @Size 를 달았더니 "@Valid 가 있는지 확인 필요" — 컨트롤러 파라미터에 이미 있다.
        // 빠진 검사는 AI 가 적는 이름이 들쭉날쭉해서 반박하지 않고, 어디 있는지만 보여 준다
        writeBoth(REQUEST, """
                package b;
                public record PostRequest(String title, String content) {
                }""");
        writeBoth(CONTROLLER, """
                package b;
                public class PostController {
                    public String create(@Valid @RequestBody PostRequest request) {
                        return request.title();
                    }
                }""");
        Finding claim = llm(Severity.MAJOR, REQUEST, 3, "MISSING_CHECK", "@Valid");

        Finding grounded = grounder.ground(List.of(claim), context(List.of(REQUEST)), false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.AI_ONLY);
        assertThat(grounded.grounding().mark(LayerId.INDEX)).isEqualTo(Mark.RELATED);
        assertThat(note(grounded, LayerId.INDEX)).contains("@Valid").contains("PostController#create");
    }

    @Test
    void ground_aiClaimsRemovedOwnerCheck_confirmsWhenNoConnectedMethodCallsIt() throws IOException {
        // sandbox #18: 삭제에서 작성자 확인을 지웠다
        write(base, SERVICE, """
                package b;
                public class PostService {
                    private Guard guard;
                    public void delete(Long id, String user) {
                        guard.requireOwner(id, user);
                        remove(id);
                    }
                    void remove(Long id) {}
                }""");
        write(head, SERVICE, """
                package b;
                public class PostService {
                    private Guard guard;
                    public void delete(Long id, String user) {
                        remove(id);
                    }
                    void remove(Long id) {}
                }""");
        writeBoth("src/main/java/b/Guard.java", """
                package b;
                public class Guard {
                    public void requireOwner(Long id, String user) {}
                }""");
        Finding claim = llm(Severity.MAJOR, SERVICE, 5, "MISSING_CHECK", "Guard#requireOwner");

        Finding grounded = grounder.ground(List.of(claim), context(List.of(SERVICE)), false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.CONFIRMED);
        assertThat(note(grounded, LayerId.INDEX)).contains("어디에도 없음").contains("requireOwner");
    }

    @Test
    void ground_missingCheckNamingTheLocationItself_isNotRefuted() throws IOException {
        // sandbox #8 · #17 재리뷰: AI 가 빠진 호출 대신 지적 위치(그 메서드 · 그 파일의 메서드)를 symbols 에 적었다.
        // 그 이름은 당연히 연결된 코드가 부르므로, "이미 있다"고 반박하면 안 된다
        writeBoth(POST, """
                package b;
                public class Post {
                    private long viewCount;
                    public void increaseViewCount() {
                        this.viewCount++;
                    }
                }""");
        writeBoth(SERVICE, """
                package b;
                public class PostService {
                    private Post post;
                    public void view() {
                        post.increaseViewCount();
                    }
                }""");
        Finding claim = llm(Severity.MINOR, POST, 5, "MISSING_CHECK", "b.Post#increaseViewCount", "Post.viewCount");

        Finding grounded = grounder.ground(List.of(claim), context(List.of(POST)), false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.AI_ONLY);
        assertThat(grounded.grounding().mark(LayerId.INDEX)).isNotEqualTo(Mark.REFUTED);
    }

    @Test
    void ground_missingCheckNamingDtoAccessor_isNotRefuted() throws IOException {
        // sandbox #7 재리뷰: "category 검증 누락" 의 symbols 가 record 접근자 PostRequest#category 였다
        writeBoth(REQUEST, """
                package b;
                public record PostRequest(String title, String category) {
                }""");
        writeBoth(CONTROLLER, """
                package b;
                public class PostController {
                    public String create(PostRequest request) {
                        return request.category();
                    }
                }""");
        Finding claim = llm(Severity.MAJOR, CONTROLLER, 4, "MISSING_CHECK", "b.PostRequest#category");

        Finding grounded = grounder.ground(List.of(claim), context(List.of(CONTROLLER)), false).get(0);

        assertThat(grounded.grounding().mark(LayerId.INDEX)).isNotEqualTo(Mark.REFUTED);
    }

    @Test
    void ground_toolAndAiOnSameMethod_confirmEachOther() throws IOException {
        write(base, SERVICE, """
                package b;
                public class PostService {
                    public String get(Long id) {
                        if (id == null) throw new IllegalStateException();
                        return "x";
                    }
                }""");
        write(head, SERVICE, """
                package b;
                public class PostService {
                    public String get(Long id) {
                        if (id == null) return null;
                        return "x";
                    }
                }""");
        AnalysisContext ctx = context(List.of(SERVICE));
        Finding tool = new Finding("AST_THROW_TO_NULL", Category.IMPACT, Severity.MAJOR, SERVICE, 4, "예외 대신 null",
                "m", null, "a", Finding.TOOL);
        Finding ai = llm(Severity.MAJOR, SERVICE, null, "BEHAVIOR_CHANGE");

        List<Finding> grounded = grounder.ground(List.of(tool, ai), ctx, false);

        assertThat(grounded.get(0).grounding().mark(LayerId.INDEX)).isEqualTo(Mark.CONFIRMED);
        assertThat(grounded.get(0).grounding().mark(LayerId.AI)).isEqualTo(Mark.CONFIRMED);
        assertThat(grounded.get(1).grounding().status()).isEqualTo(Status.CONFIRMED);
        assertThat(note(grounded.get(1), LayerId.INDEX)).contains("AST_THROW_TO_NULL");
    }

    @Test
    void ground_aiOnlyClaim_keepsContextAndCountsInVerdict() throws IOException {
        write(base, SERVICE, """
                package b;
                public class PostService {
                    public int count() { return 1; }
                    public int total() { return count(); }
                }""");
        write(head, SERVICE, """
                package b;
                public class PostService {
                    public int count() { return 2; }
                    public int total() { return count(); }
                }""");
        Finding ai = llm(Severity.MAJOR, SERVICE, 3, "BEHAVIOR_CHANGE");

        Finding grounded = grounder.ground(List.of(ai), context(List.of(SERVICE)), false).get(0);

        assertThat(grounded.grounding().status()).isEqualTo(Status.AI_ONLY);
        assertThat(grounded.grounding().mark(LayerId.INDEX)).isEqualTo(Mark.RELATED);
        assertThat(note(grounded, LayerId.INDEX)).contains("바뀐 메서드 PostService#count()").contains("호출부 1곳");
        assertThat(note(grounded, LayerId.RUNTIME)).isEqualTo("실행 검증 꺼짐");
        assertThat(verdicts.decide(List.of(grounded))).isEqualTo(Verdict.NEEDS_CHANGES);
    }

    private AnalysisContext context(List<String> changed) {
        JavaIndexer indexer = new JavaIndexer();
        RepoIndex baseIndex = indexer.index(base);
        RepoIndex headIndex = indexer.index(head);
        AnalysisContext ctx = new AnalysisContext(
                new PullInfo(new RepoRef("o", "r"), 1, "t", "", "a", "main", "f", "h", List.of()),
                changed.stream().map(f -> new ChangedFile(f, "modified", 1, 1, "", null, Patch.EMPTY)).toList());
        ctx.setCheckout(new Checkout(base, base, head, "b", "h"));
        ctx.setIndexes(baseIndex, headIndex, MethodDiff.compute(baseIndex, headIndex));
        return ctx;
    }

    private static Finding llm(Severity severity, String file, Integer line, String kind, String... symbols) {
        return new Finding("LLM_REVIEW", Category.GENERAL, severity, file, line, "t", "m", null, "t", Finding.LLM,
                Grounding.claim(kind, List.of(symbols)));
    }

    private static String note(Finding f, LayerId id) {
        return f.grounding().layers().stream().filter(l -> l.layer() == id).findFirst().orElseThrow().note();
    }

    private void writeBoth(String path, String content) throws IOException {
        write(base, path, content);
        write(head, path, content);
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
