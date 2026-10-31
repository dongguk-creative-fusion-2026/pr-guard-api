package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.analysis.rules.ConventionAnalyzer;
import com.prguard.analysis.rules.StaleBaseRuleAnalyzer;
import com.prguard.diff.Patch;
import com.prguard.github.RepoRef;
import com.prguard.index.JavaIndexer;
import com.prguard.index.MethodDiff;
import com.prguard.index.RepoIndex;
import com.prguard.rulemap.Rule;
import com.prguard.rulemap.RuleExtractor;
import com.prguard.rulemap.RuleMap;
import com.prguard.workspace.Checkout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleMapTest {

    private static final String NOT_FOUND = "src/main/java/b/NotFoundException.java";
    private static final String GUARD = "src/main/java/b/OwnershipGuard.java";
    private static final String USER = "src/main/java/b/User.java";
    private static final String POST_SERVICE = "src/main/java/b/PostService.java";
    private static final String COMMENT_SERVICE = "src/main/java/b/CommentService.java";
    private static final String CONTROLLER = "src/main/java/b/UserController.java";
    private static final String RESPONSE = "src/main/java/b/PostResponse.java";

    @TempDir
    Path base;

    @TempDir
    Path head;

    @TempDir
    Path latest;

    @Test
    void extract_factsAndConventions() throws IOException {
        board(base);

        List<Rule> rules = RuleExtractor.extract(new JavaIndexer().index(base), base);

        assertThat(statements(rules)).contains(
                "PostService#getPost(Long): 찾는 값이 없으면 NotFoundException 예외를 던진다 → HTTP 404",
                "PostService#delete(Long,User): OwnershipGuard#requireOwner 로 검사한다",
                "User.bio: null 일 수 있다, 최대 300자",
                "User.email: NOT NULL");
        assertThat(rules).filteredOn(r -> r.kind() == Rule.Kind.CONVENTION).extracting(Rule::subject)
                .contains(RuleExtractor.WRITE_GUARD, RuleExtractor.LOOKUP_THROWS);
    }

    @Test
    void staleBase_mainChangedLookupToNull_afterPrBranched_warns() throws IOException {
        // 철수는 base 를 보고 getPost(없으면 예외)에 기대는 코드를 짰는데, 그 뒤 main 에서 getPost 가 null 을 돌려주게 됐다
        board(base);
        board(head);
        write(head, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private PostService postService;
                    private OwnershipGuard guard;
                    public String titleOf(Long postId) {
                        return postService.getPost(postId).title();
                    }
                }""");
        board(latest);
        write(latest, POST_SERVICE, """
                package b;
                public class PostService {
                    private Repo posts;
                    private OwnershipGuard guard;
                    public Post getPost(Long id) {
                        return posts.findById(id).orElse(null);
                    }
                    public void delete(Long id, User user) {
                        guard.requireOwner(id, user);
                    }
                    public void update(Long id, User user) {
                        guard.requireOwner(id, user);
                    }
                }""");
        AnalysisContext ctx = context(List.of(COMMENT_SERVICE));
        ctx.setRuleMaps(map(base, "b"), map(latest, "l"));

        List<Finding> findings = new StaleBaseRuleAnalyzer().analyze(ctx);

        assertThat(findings).hasSize(1);
        Finding f = findings.get(0);
        assertThat(f.severity()).isEqualTo(Severity.MAJOR);
        assertThat(f.title()).contains("PostService#getPost(Long)");
        assertThat(f.message()).contains("사라짐: PostService#getPost(Long): 찾는 값이 없으면 NotFoundException")
                .contains("생김: PostService#getPost(Long): null 을 돌려줄 수 있다")
                .contains("CommentService#titleOf(Long)");
    }

    @Test
    void staleBase_sameCommit_silent() throws IOException {
        board(base);
        board(head);
        AnalysisContext ctx = context(List.of(POST_SERVICE));
        ctx.setRuleMaps(map(base, "b"), null);

        assertThat(new StaleBaseRuleAnalyzer().analyze(ctx)).isEmpty();
    }

    @Test
    void convention_newDeleteWithoutGuard_andEntityReturnedFromApi_flagged() throws IOException {
        // sandbox #6: 새 일괄 삭제에 작성자 확인 없음, #13: 엔티티(User, email 포함)를 API 가 그대로 돌려줌
        board(base);
        board(head);
        write(head, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private OwnershipGuard guard;
                    public void deleteAllOfPost(Long postId) {
                    }
                }""");
        write(head, CONTROLLER, """
                package b;
                @RestController
                public class UserController {
                    private UserService userService;
                    @GetMapping("/api/users/{id}")
                    public User profile(Long id) {
                        return null;
                    }
                }""");
        AnalysisContext ctx = context(List.of(COMMENT_SERVICE, CONTROLLER));
        ctx.setRuleMaps(map(base, "b"), null);

        List<Finding> findings = new ConventionAnalyzer().analyze(ctx);

        assertThat(findings).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("CONVENTION_GUARD_MISSING", "CONVENTION_SENSITIVE_RESPONSE");
        assertThat(findings).allMatch(f -> f.category() == Category.SECURITY && f.severity() == Severity.MAJOR);
    }

    @Test
    void convention_cleanAdditions_silent() throws IOException {
        board(base);
        board(head);
        write(head, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private OwnershipGuard guard;
                    public void deleteAllOfPost(Long postId, User user) {
                        guard.requireOwner(postId, user);
                    }
                }""");
        AnalysisContext ctx = context(List.of(COMMENT_SERVICE));
        ctx.setRuleMaps(map(base, "b"), null);

        assertThat(new ConventionAnalyzer().analyze(ctx)).isEmpty();
    }

    /** 게시판: 없으면 404 인 조회, 소유자 확인을 하는 수정·삭제, bio 가 null 일 수 있는 회원, 민감 필드 없는 응답 DTO */
    private static void board(Path root) throws IOException {
        write(root, NOT_FOUND, """
                package b;
                @ResponseStatus(HttpStatus.NOT_FOUND)
                public class NotFoundException extends RuntimeException {
                    public NotFoundException(String message) { super(message); }
                }""");
        write(root, GUARD, """
                package b;
                public class OwnershipGuard {
                    public void requireOwner(Long id, User user) {
                    }
                }""");
        write(root, USER, """
                package b;
                @Entity
                public class User {
                    @Id
                    private Long id;

                    @Column(nullable = false, unique = true)
                    private String email;

                    @Column(length = 300)
                    private String bio;
                }""");
        write(root, POST_SERVICE, """
                package b;
                public class PostService {
                    private Repo posts;
                    private OwnershipGuard guard;
                    public Post getPost(Long id) {
                        return posts.findById(id)
                                .orElseThrow(() -> new NotFoundException("게시글이 없습니다: " + id));
                    }
                    public void delete(Long id, User user) {
                        guard.requireOwner(id, user);
                    }
                    public void update(Long id, User user) {
                        guard.requireOwner(id, user);
                    }
                }""");
        write(root, COMMENT_SERVICE, """
                package b;
                public class CommentService {
                    private OwnershipGuard guard;
                    public Comment find(Long id) {
                        return comments.findById(id).orElseThrow(() -> new NotFoundException("댓글이 없습니다"));
                    }
                }""");
        write(root, RESPONSE, """
                package b;
                public record PostResponse(Long id, String title) {
                }""");
        write(root, "src/main/java/b/CommentResponse.java", """
                package b;
                public record CommentResponse(Long id, String content) {
                }""");
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

    private static RuleMap map(Path root, String sha) {
        return new RuleMap(sha, RuleExtractor.extract(new JavaIndexer().index(root), root), false);
    }

    private static List<String> statements(List<Rule> rules) {
        return rules.stream().map(Rule::statement).toList();
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
