package com.prguard.project;

import com.prguard.common.ApiException;
import com.prguard.github.GitHubClient;
import com.prguard.github.GitHubException;
import com.prguard.github.GitHubRepo;
import com.prguard.github.RepoRef;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final GitHubClient github;

    public ProjectService(ProjectRepository projects, GitHubClient github) {
        this.projects = projects;
        this.github = github;
    }

    public List<Project> list() {
        return projects.findAll();
    }

    public Project get(long id) {
        return projects.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "프로젝트가 없습니다: " + id));
    }

    /** public GitHub 레포 URL 을 받아 프로젝트로 등록한다. */
    public Project register(String url) {
        RepoRef ref;
        try {
            ref = RepoRef.parse(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPO_URL", e.getMessage());
        }

        GitHubRepo repo;
        try {
            repo = github.getRepo(ref);
        } catch (GitHubException e) {
            if (e.status() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REPO_NOT_FOUND",
                        "레포를 찾을 수 없습니다 (없거나 private): " + ref.fullName());
            }
            throw e;
        }
        if (repo.isPrivate()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REPO_NOT_PUBLIC", "public 레포만 등록할 수 있습니다: " + ref.fullName());
        }

        // owner/name 은 GitHub 이 돌려준 표기로 저장한다 (대소문자, 이름 변경 리다이렉트 반영)
        String owner = repo.owner().login();
        if (projects.findIdByRepo(owner, repo.name()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_EXISTS", "이미 등록된 레포입니다: " + owner + "/" + repo.name());
        }
        long id = projects.insert(owner, repo.name(), repo.htmlUrl(), repo.defaultBranch())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROJECT_EXISTS",
                        "이미 등록된 레포입니다: " + owner + "/" + repo.name()));
        return get(id);
    }

    public void delete(long id) {
        if (!projects.delete(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "프로젝트가 없습니다: " + id);
        }
    }
}
