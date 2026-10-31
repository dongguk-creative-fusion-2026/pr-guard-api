package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.ContractContext.Role;
import com.prguard.analysis.ContractContext.Snippet;
import com.prguard.analysis.Grounding.LayerId;
import com.prguard.analysis.Grounding.Mark;
import com.prguard.analysis.Grounding.Status;
import com.prguard.diff.Patch;
import com.prguard.github.RepoRef;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import com.prguard.review.ConflictReview;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractContextTest {

    private static final String NOT_FOUND = "src/main/java/b/NotFoundException.java";
    private static final String POST_SERVICE = "src/main/java/b/PostService.java";
    private static final String COMMENT_SERVICE = "src/main/java/b/CommentService.java";
    private static final String CONTROLLER = "src/main/java/b/CommentController.java";
    private static final String TEST = "src/test/java/b/CommentServiceTest.java";

    @TempDir
    Path base;

    @TempDir
    Path head;

    @Test
    void collect_droppedLookupThatThrewNotFound_collectsOriginalContract() throws IOException {
        // sandbox #15: 댓글 작성에서 getPost(없으면 404) 대신 getReference(조회 없음)를 부른다
        sandbox15();

        ContractContext contract = ContractContext.collect(context(), 4, 9000);

        assertThat(contract.targets()).hasSize(1);
        assertThat(contract.targets().get(0).method().id()).isEqualTo("b.CommentService#add(Long,String)");
        List<Snippet> snippets = contract.targets().get(0).snippets();
        assertThat(snippets).extracting(Snippet::role).containsExactly(
                Role.BASE_BODY, Role.DROPPED_CALLEE, Role.NEW_CALLEE, Role.LOST_EXCEPTION, Role.CALLER, Role.TEST);
        assertThat(snippet(snippets, Role.DROPPED_CALLEE).symbol()).isEqualTo("b.PostService#getPost(Long)");
        assertThat(snippet(snippets, Role.DROPPED_CALLEE).code()).contains("orElseThrow");
        assertThat(snippet(snippets, Role.LOST_EXCEPTION).code()).contains("@ResponseStatus");
        assertThat(snippet(snippets, Role.CALLER).symbol()).isEqualTo("b.CommentController#add(Long,String)");
        assertThat(snippet(snippets, Role.TEST).code()).contains("assertThrows");

        String rendered = contract.render();
        assertThat(rendered).contains("[C2] PR 전에는 부르다 이제 안 부르는 메서드 PostService#getPost(Long)")
                .contains("getReference");
    }

    @Test
    void collect_onlyAddedMethods_isEmpty() throws IOException {
        writeBoth(POST_SERVICE, """
                package b;
                public class PostService {
                }""");
        write(head, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    public String hello() { return "hi"; }
                }""");

        assertThat(ContractContext.collect(context(), 4, 9000).isEmpty()).isTrue();
    }

    @Test
    void conflictFinding_citingCollectedCode_keptWithContext_andInventedRefs_refuted() throws IOException {
        sandbox15();
        AnalysisContext ctx = context();
        ContractContext contract = ContractContext.collect(ctx, 4, 9000);
        ConflictReview review = new ConflictReview(List.of(
                new ConflictReview.Item("MAJOR", "b.CommentService#add(Long,String)", COMMENT_SERVICE, null,
                        "없는 게시글에 댓글을 달면 404 대신 500", "m", List.of("C2", "[C4]")),
                new ConflictReview.Item("MAJOR", "b.CommentService#add(Long,String)", COMMENT_SERVICE, null,
                        "지어낸 근거", "m", List.of("C99"))));

        List<Finding> findings = AnalysisPipeline.toConflictFindings(review, contract, ctx);
        List<Finding> grounded = new FindingGrounder(new AnalysisProperties(500, 2, 0.5, 6000))
                .ground(findings, ctx, false);

        Finding real = grounded.get(0);
        assertThat(real.ruleId()).isEqualTo("LLM_CONFLICT");
        assertThat(real.category()).isEqualTo(Category.IMPACT);
        assertThat(real.grounding().status()).isEqualTo(Status.AI_ONLY);
        assertThat(real.grounding().mark(LayerId.INDEX)).isEqualTo(Mark.RELATED);
        assertThat(real.evidence()).contains("PostService#getPost(Long)").contains("NotFoundException");

        Finding invented = grounded.get(1);
        assertThat(invented.grounding().status()).isEqualTo(Status.REFUTED);
        assertThat(invented.refuted()).isTrue();
    }

    private void sandbox15() throws IOException {
        writeBoth(NOT_FOUND, """
                package b;
                @ResponseStatus(HttpStatus.NOT_FOUND)
                public class NotFoundException extends RuntimeException {
                    public NotFoundException(String message) { super(message); }
                }""");
        write(base, POST_SERVICE, """
                package b;
                public class PostService {
                    private Repo posts;
                    public Post getPost(Long id) {
                        return posts.findById(id)
                                .orElseThrow(() -> new NotFoundException("게시글이 없습니다: " + id));
                    }
                }""");
        write(head, POST_SERVICE, """
                package b;
                public class PostService {
                    private Repo posts;
                    public Post getPost(Long id) {
                        return posts.findById(id)
                                .orElseThrow(() -> new NotFoundException("게시글이 없습니다: " + id));
                    }
                    public Post getReference(Long id) {
                        return posts.getReferenceById(id);
                    }
                }""");
        write(base, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private PostService postService;
                    public String add(Long postId, String content) {
                        Post post = postService.getPost(postId);
                        return content;
                    }
                }""");
        write(head, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private PostService postService;
                    public String add(Long postId, String content) {
                        Post post = postService.getReference(postId);
                        return content;
                    }
                }""");
        writeBoth(CONTROLLER, """
                package b;
                public class CommentController {
                    private CommentService commentService;
                    public String add(Long postId, String content) {
                        return commentService.add(postId, content);
                    }
                }""");
        writeBoth(TEST, """
                package b;
                class CommentServiceTest {
                    private CommentService service;
                    void add_missingPost_throwsNotFound() {
                        assertThrows(NotFoundException.class, () -> service.add(99L, "c"));
                    }
                }""");
    }

    private AnalysisContext context() {
        JavaIndexer indexer = new JavaIndexer();
        RepoIndex baseIndex = indexer.index(base);
        RepoIndex headIndex = indexer.index(head);
        AnalysisContext ctx = new AnalysisContext(
                new PullInfo(new RepoRef("o", "r"), 15, "댓글 작성 시 게시글 SELECT 생략", "", "a", "main", "f", "h",
                        List.of()),
                List.of(new ChangedFile(COMMENT_SERVICE, "modified", 1, 1, "", null, Patch.EMPTY),
                        new ChangedFile(POST_SERVICE, "modified", 3, 0, "", null, Patch.EMPTY)));
        ctx.setCheckout(new Checkout(base, base, head, "b", "h"));
        ctx.setIndexes(baseIndex, headIndex, MethodDiff.compute(baseIndex, headIndex));
        return ctx;
    }

    private static Snippet snippet(List<Snippet> snippets, Role role) {
        return snippets.stream().filter(s -> s.role() == role).findFirst().orElseThrow();
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
