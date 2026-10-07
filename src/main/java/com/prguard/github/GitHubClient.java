package com.prguard.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GitHubClient {

    private static final int PAGE_SIZE = 100;
    // 변경 파일 API 는 최대 3000개지만 리뷰 입력으로는 300개면 충분하다
    private static final int MAX_FILE_PAGES = 3;

    private final RestClient http;
    private final GitHubProperties props;

    public GitHubClient(RestClient.Builder builder, GitHubProperties props) {
        this.props = props;
        RestClient.Builder b = builder.clone()
                .baseUrl(props.apiUrl())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {
                    throw new GitHubException(res.getStatusCode().value(),
                            req.getMethod() + " " + req.getURI().getPath() + " -> " + res.getStatusCode().value());
                });
        if (props.hasToken()) {
            b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.token());
        }
        this.http = b.build();
    }

    public boolean canWrite() {
        return props.hasToken() && props.commentEnabled();
    }

    public GitHubRepo getRepo(RepoRef repo) {
        return http.get().uri("/repos/{o}/{r}", repo.owner(), repo.name())
                .retrieve().body(GitHubRepo.class);
    }

    /** 레포의 언어별 코드 크기 (바이트) */
    public Map<String, Long> getLanguages(RepoRef repo) {
        Map<String, Long> languages = http.get().uri("/repos/{o}/{r}/languages", repo.owner(), repo.name())
                .retrieve().body(new ParameterizedTypeReference<Map<String, Long>>() {});
        return languages == null ? Map.of() : languages;
    }

    /** ETag 를 주면 변경이 없을 때 304 로 끝난다. 304 는 rate limit 에 잡히지 않는다. */
    public OpenPulls listOpenPulls(RepoRef repo, String etag) {
        return http.get()
                .uri("/repos/{o}/{r}/pulls?state=open&sort=updated&direction=desc&per_page={n}",
                        repo.owner(), repo.name(), PAGE_SIZE)
                .headers(h -> {
                    if (etag != null) {
                        h.setIfNoneMatch(etag);
                    }
                })
                .exchange((req, res) -> {
                    if (res.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                        return new OpenPulls(true, etag, List.of());
                    }
                    if (res.getStatusCode().isError()) {
                        throw new GitHubException(res.getStatusCode().value(),
                                "GET pulls " + repo.fullName() + " -> " + res.getStatusCode().value());
                    }
                    List<GitHubPull> pulls = res.bodyTo(new ParameterizedTypeReference<List<GitHubPull>>() {});
                    return new OpenPulls(false, res.getHeaders().getETag(), pulls == null ? List.of() : pulls);
                });
    }

    public GitHubPull getPull(RepoRef repo, int number) {
        return http.get().uri("/repos/{o}/{r}/pulls/{n}", repo.owner(), repo.name(), number)
                .retrieve().body(GitHubPull.class);
    }

    /** PR 의 커밋 메시지 (오래된 순, 최대 250개). */
    public List<String> listPullCommitMessages(RepoRef repo, int number) {
        List<GitHubCommit> commits = http.get()
                .uri("/repos/{o}/{r}/pulls/{n}/commits?per_page={size}", repo.owner(), repo.name(), number, PAGE_SIZE)
                .retrieve()
                .body(new ParameterizedTypeReference<List<GitHubCommit>>() {});
        return commits == null ? List.of() : commits.stream().map(c -> c.commit().message()).toList();
    }

    public List<GitHubPullFile> listPullFiles(RepoRef repo, int number) {
        List<GitHubPullFile> files = new ArrayList<>();
        for (int page = 1; page <= MAX_FILE_PAGES; page++) {
            List<GitHubPullFile> chunk = http.get()
                    .uri("/repos/{o}/{r}/pulls/{n}/files?per_page={size}&page={page}",
                            repo.owner(), repo.name(), number, PAGE_SIZE, page)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<GitHubPullFile>>() {});
            if (chunk == null || chunk.isEmpty()) {
                break;
            }
            files.addAll(chunk);
            if (chunk.size() < PAGE_SIZE) {
                break;
            }
        }
        return files;
    }

    public GitHubComment createComment(RepoRef repo, int number, String body) {
        return http.post().uri("/repos/{o}/{r}/issues/{n}/comments", repo.owner(), repo.name(), number)
                .body(new CommentBody(body))
                .retrieve().body(GitHubComment.class);
    }

    public GitHubComment updateComment(RepoRef repo, long commentId, String body) {
        return http.patch().uri("/repos/{o}/{r}/issues/comments/{id}", repo.owner(), repo.name(), commentId)
                .body(new CommentBody(body))
                .retrieve().body(GitHubComment.class);
    }

    /**
     * 라인 코멘트를 묶어 리뷰 하나로 남긴다 (event=COMMENT, 승인·거절 아님).
     * 라인이 diff 밖이면 GitHub 이 422 를 준다.
     */
    public GitHubReview createReview(RepoRef repo, int number, String commitId, String body,
                                     List<ReviewComment> comments) {
        return http.post().uri("/repos/{o}/{r}/pulls/{n}/reviews", repo.owner(), repo.name(), number)
                .body(new ReviewBody(commitId, body, "COMMENT", comments))
                .retrieve().body(GitHubReview.class);
    }

    /**
     * workflow_dispatch 로 워크플로를 실행한다. 레포에 Actions 쓰기 권한이 있는 토큰이 필요하다.
     *
     * @param token 기본 토큰과 다른 토큰을 쓸 때 (null 이면 기본 토큰)
     */
    public void dispatchWorkflow(RepoRef repo, String workflow, String ref, Map<String, String> inputs, String token) {
        http.post().uri("/repos/{o}/{r}/actions/workflows/{w}/dispatches", repo.owner(), repo.name(), workflow)
                .headers(h -> {
                    if (token != null && !token.isBlank()) {
                        h.setBearerAuth(token);
                    }
                })
                .body(new DispatchBody(ref, inputs))
                .retrieve()
                .toBodilessEntity();
    }

    public GitHubWorkflowRun getWorkflowRun(RepoRef repo, long runId) {
        return http.get().uri("/repos/{o}/{r}/actions/runs/{id}", repo.owner(), repo.name(), runId)
                .retrieve().body(GitHubWorkflowRun.class);
    }

    /** 기본 브랜치의 파일 하나. 없으면 null */
    public RepoFile getFile(RepoRef repo, String path) {
        return getFile(repo, path, null);
    }

    /** @param ref 브랜치 (null 이면 기본 브랜치) */
    public RepoFile getFile(RepoRef repo, String path, String ref) {
        JsonNode node;
        try {
            node = (ref == null
                    ? http.get().uri("/repos/{o}/{r}/contents/{path}", repo.owner(), repo.name(), path)
                    : http.get().uri("/repos/{o}/{r}/contents/{path}?ref={ref}", repo.owner(), repo.name(), path, ref))
                    .retrieve().body(JsonNode.class);
        } catch (GitHubException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
        if (node == null || !"file".equals(node.path("type").asText())) {
            return null;
        }
        String content = new String(Base64.getMimeDecoder().decode(node.path("content").asText("")), StandardCharsets.UTF_8);
        return new RepoFile(path, node.path("sha").asText(), node.path("html_url").asText(), content);
    }

    /** 기본 브랜치 루트의 파일 · 디렉터리 이름 */
    public List<String> listRoot(RepoRef repo) {
        JsonNode node = http.get().uri("/repos/{o}/{r}/contents", repo.owner(), repo.name()).retrieve().body(JsonNode.class);
        List<String> names = new ArrayList<>();
        if (node != null) {
            node.forEach(n -> names.add(n.path("name").asText()));
        }
        return names;
    }

    /** 이 서버 토큰으로 레포에 push 할 수 있는가 (브랜치 · PR 을 만들 수 있는가) */
    public boolean canPush(RepoRef repo) {
        if (!props.hasToken()) {
            return false;
        }
        JsonNode node = http.get().uri("/repos/{o}/{r}", repo.owner(), repo.name()).retrieve().body(JsonNode.class);
        return node != null && node.path("permissions").path("push").asBoolean(false);
    }

    /** 브랜치 끝 커밋. 없으면 null */
    public String branchSha(RepoRef repo, String branch) {
        try {
            JsonNode node = http.get().uri("/repos/{o}/{r}/git/ref/heads/{b}", repo.owner(), repo.name(), branch)
                    .retrieve().body(JsonNode.class);
            return node == null ? null : node.path("object").path("sha").asText(null);
        } catch (GitHubException e) {
            if (e.status() == 404) {
                return null;
            }
            throw e;
        }
    }

    public void createBranch(RepoRef repo, String branch, String sha) {
        http.post().uri("/repos/{o}/{r}/git/refs", repo.owner(), repo.name())
                .body(Map.of("ref", "refs/heads/" + branch, "sha", sha))
                .retrieve().toBodilessEntity();
    }

    /** 브랜치에 파일을 만들거나(sha 없음) 고친다(sha = 지금 파일). 커밋이 하나 생긴다 */
    public void putFile(RepoRef repo, String branch, String path, String message, String content, String sha) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (sha != null) {
            body.put("sha", sha);
        }
        http.put().uri("/repos/{o}/{r}/contents/{path}", repo.owner(), repo.name(), path)
                .body(body).retrieve().toBodilessEntity();
    }

    /** PR 을 만들고 주소를 돌려준다 */
    public String createPull(RepoRef repo, String head, String base, String title, String body) {
        JsonNode node = http.post().uri("/repos/{o}/{r}/pulls", repo.owner(), repo.name())
                .body(Map.of("head", head, "base", base, "title", title, "body", body))
                .retrieve().body(JsonNode.class);
        return node == null ? null : node.path("html_url").asText(null);
    }

    /** 이 브랜치에서 열린 PR 주소. 없으면 null */
    public String openPullFor(RepoRef repo, String branch) {
        JsonNode node = http.get().uri("/repos/{o}/{r}/pulls?state=open&head={h}", repo.owner(), repo.name(),
                repo.owner() + ":" + branch).retrieve().body(JsonNode.class);
        return node != null && node.size() > 0 ? node.get(0).path("html_url").asText(null) : null;
    }

    /** @param content 디코딩한 내용 (UTF-8) */
    public record RepoFile(String path, String sha, String htmlUrl, String content) {
    }

    private record DispatchBody(String ref, Map<String, String> inputs) {
    }

    /** @param side head 쪽 라인이면 RIGHT */
    public record ReviewComment(String path, int line, String side, String body) {
    }

    private record ReviewBody(@JsonProperty("commit_id") String commitId, String body, String event,
                              List<ReviewComment> comments) {
    }

    private record CommentBody(String body) {
    }
}
