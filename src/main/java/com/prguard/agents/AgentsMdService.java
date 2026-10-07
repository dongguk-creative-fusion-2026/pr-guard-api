package com.prguard.agents;

import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubClient.RepoFile;
import com.prguard.github.GitHubException;
import com.prguard.github.RepoRef;
import com.prguard.project.Project;
import com.prguard.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * AI 코딩 에이전트 규칙 파일(AGENTS.md).
 *
 * 레포에 이미 있는 에이전트 규칙 파일과 빌드 도구를 알려 주고(초안은 웹이 레포 그래프로 만든다),
 * 사용자가 고르면 초안을 레포에 PR 로 올린다. 레포에 직접 push 하지 않고 늘 PR 로만 제안한다.
 */
@Service
public class AgentsMdService {

    private static final Logger log = LoggerFactory.getLogger(AgentsMdService.class);
    /** 에이전트들이 읽는 규칙 파일 (도구마다 이름이 다르다) */
    static final List<String> RULE_FILES = List.of("AGENTS.md", "CLAUDE.md", ".github/copilot-instructions.md",
            ".cursorrules");
    static final String PATH = "AGENTS.md";
    static final String BRANCH = "pr-guard/agents-md";
    private static final int MAX_CONTENT = 60_000;
    static final Pattern GRADLE_JAVA = Pattern.compile("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)|sourceCompatibility\\s*=\\s*['\"]?(?:JavaVersion\\.VERSION_)?(\\d+)");
    static final Pattern MAVEN_JAVA = Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source)>\\s*(\\d+)");

    private final ProjectService projects;
    private final GitHubClient github;

    public AgentsMdService(ProjectService projects, GitHubClient github) {
        this.projects = projects;
        this.github = github;
    }

    /**
     * @param existing    레포에 이미 있는 규칙 파일들 (내용 포함)
     * @param canCreatePr 이 서버 토큰으로 브랜치 · PR 을 만들 수 있는가
     * @param openPr      전에 올린 AGENTS.md PR 이 아직 열려 있으면 그 주소
     */
    public record Info(List<ExistingFile> existing, Build build, boolean canCreatePr, String openPr, String defaultBranch) {
    }

    public record ExistingFile(String path, String htmlUrl, String content) {
    }

    /**
     * @param tool      gradle · maven · npm · unknown
     * @param wrapper   gradlew · mvnw 가 있는가
     * @param build     빌드 명령
     * @param test      테스트 명령
     * @param java      빌드 파일에 적힌 Java 버전 (없으면 null)
     */
    public record Build(String tool, boolean wrapper, String build, String test, Integer java) {
    }

    public Info info(long projectId) {
        Project project = projects.get(projectId);
        RepoRef repo = project.ref();
        try {
            List<ExistingFile> existing = new ArrayList<>();
            for (String path : RULE_FILES) {
                RepoFile f = github.getFile(repo, path);
                if (f != null) {
                    existing.add(new ExistingFile(f.path(), f.htmlUrl(), cut(f.content())));
                }
            }
            boolean canPush = github.canPush(repo);
            return new Info(existing, build(repo), canPush, canPush ? github.openPullFor(repo, BRANCH) : null,
                    project.defaultBranch());
        } catch (GitHubException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ERROR", "GitHub 에서 레포 파일을 읽지 못했습니다: " + e.getMessage());
        }
    }

    /** 루트 파일로 빌드 도구와 명령을 정한다 */
    Build build(RepoRef repo) {
        List<String> root = github.listRoot(repo);
        if (root.contains("build.gradle") || root.contains("build.gradle.kts") || root.contains("gradlew")) {
            boolean wrapper = root.contains("gradlew");
            String cmd = wrapper ? "./gradlew" : "gradle";
            RepoFile f = github.getFile(repo, root.contains("build.gradle.kts") ? "build.gradle.kts" : "build.gradle");
            return new Build("gradle", wrapper, cmd + " build", cmd + " test", f == null ? null : javaVersion(GRADLE_JAVA, f.content()));
        }
        if (root.contains("pom.xml") || root.contains("mvnw")) {
            boolean wrapper = root.contains("mvnw");
            String cmd = wrapper ? "./mvnw" : "mvn";
            RepoFile f = github.getFile(repo, "pom.xml");
            return new Build("maven", wrapper, cmd + " -B package", cmd + " -B test", f == null ? null : javaVersion(MAVEN_JAVA, f.content()));
        }
        if (root.contains("package.json")) {
            return new Build("npm", false, "npm run build", "npm test", null);
        }
        return new Build("unknown", false, null, null, null);
    }

    static Integer javaVersion(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return Integer.valueOf(m.group(i));
            }
        }
        return null;
    }

    /**
     * 내용을 새 브랜치에 커밋하고 기본 브랜치로 PR 을 연다. 이미 열린 PR 이 있으면 그 브랜치를 고친다.
     * 레포에 AGENTS.md 가 이미 있으면 그 파일을 고치는 PR 이 된다 (반복된 실수 규칙 추가 등)
     */
    public String createPull(long projectId, String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGENTS_MD", "내용이 비었거나 너무 깁니다");
        }
        Project project = projects.get(projectId);
        RepoRef repo = project.ref();
        try {
            if (!github.canPush(repo)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "NO_PUSH_PERMISSION",
                        "PR Guard 봇이 " + repo.fullName() + " 에 브랜치를 만들 권한이 없습니다. 내용을 복사해 직접 올려 주세요");
            }
            RepoFile current = github.getFile(repo, PATH);
            if (current != null && current.content().strip().equals(content.strip())) {
                throw new ApiException(HttpStatus.CONFLICT, "AGENTS_MD_UNCHANGED", "레포의 AGENTS.md 와 내용이 같습니다");
            }
            String open = github.openPullFor(repo, BRANCH);
            String sha = github.branchSha(repo, BRANCH);
            if (sha == null) {
                String base = github.branchSha(repo, project.defaultBranch());
                github.createBranch(repo, BRANCH, base);
            }
            // 브랜치에 이미 파일이 있으면(기존 파일 · 전에 올린 초안) 그 sha 로 고친다
            String fileSha = existingSha(repo, BRANCH);
            String title = current == null ? "AGENTS.md 추가: AI 코딩 에이전트용 레포 규칙" : "AGENTS.md 갱신: 리뷰에서 반복된 실수를 규칙으로 추가";
            github.putFile(repo, BRANCH, PATH, title, content, fileSha);
            if (open != null) {
                return open;
            }
            if (current != null) {
                return github.createPull(repo, BRANCH, project.defaultBranch(), title, """
                        PR Guard 리뷰에서 여러 PR 에 걸쳐 반복된 실수를 AI 코딩 에이전트용 규칙으로 AGENTS.md 에 더합니다.
                        규칙마다 근거가 된 PR 번호를 적어 두었습니다. 팀에 맞게 고친 뒤 머지해 주세요.
                        """);
            }
            return github.createPull(repo, BRANCH, project.defaultBranch(), title, """
                    AI 코딩 에이전트(Claude Code · Codex · Cursor 등)가 이 레포에서 작업할 때 읽는 규칙 파일입니다.

                    PR Guard 가 레포 분석(코드 그래프 · git 이력)으로 만든 초안입니다:
                    빌드 · 테스트 명령, 구조와 기능 묶음, 바꿀 때 조심할 곳, 함께 바뀌어야 하는 파일, 테스트 · PR 규칙.
                    팀에 맞게 고친 뒤 머지해 주세요.
                    """);
        } catch (GitHubException e) {
            log.warn("AGENTS.md PR 실패 {}: {}", repo.fullName(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_ERROR", "PR 을 만들지 못했습니다: " + e.getMessage());
        }
    }

    private String existingSha(RepoRef repo, String branch) {
        RepoFile f = github.getFile(repo, PATH, branch);
        return f == null ? null : f.sha();
    }

    private static String cut(String s) {
        return s.length() > MAX_CONTENT ? s.substring(0, MAX_CONTENT) : s;
    }
}
