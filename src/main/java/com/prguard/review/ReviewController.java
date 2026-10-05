package com.prguard.review;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.prguard.analysis.FindingRepository;
import com.prguard.common.ApiException;
import com.prguard.project.ProjectService;
import com.prguard.pull.PullRequest;
import com.prguard.pull.PullRequestRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ProjectService projects;
    private final ReviewRepository reviews;
    private final FindingRepository findings;
    private final PullRequestRepository pulls;

    public ReviewController(ProjectService projects, ReviewRepository reviews, FindingRepository findings,
                            PullRequestRepository pulls) {
        this.projects = projects;
        this.reviews = reviews;
        this.findings = findings;
        this.pulls = pulls;
    }

    @GetMapping("/api/projects/{id}/reviews")
    public List<Review> list(@PathVariable long id, @RequestParam(defaultValue = "20") int limit) {
        projects.get(id);
        return reviews.findByProject(id, Math.clamp(limit, 1, 100));
    }

    /** 리뷰 하나와 지적 사항, 분석 재료 요약. */
    @GetMapping("/api/reviews/{id}")
    public ReviewDetail get(@PathVariable long id) {
        Review review = reviews.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "리뷰가 없습니다: " + id));
        return new ReviewDetail(review, findings.findByReview(id), reviews.context(id).orElse(null));
    }

    /** PR 의 현재 커밋을 다시 리뷰한다. */
    @PostMapping("/api/projects/{id}/pulls/{number}/reviews")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Review rerun(@PathVariable long id, @PathVariable int number) {
        projects.get(id);
        PullRequest pr = pulls.find(id, number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PULL_NOT_FOUND", "PR 이 없습니다: #" + number));
        if (!pr.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "PULL_CLOSED", "닫힌 PR 은 다시 리뷰할 수 없습니다: #" + number);
        }
        long reviewId = reviews.createRerun(id, number, pr.headSha());
        return reviews.findById(reviewId).orElseThrow();
    }

    /** @param context 분석 재료 요약 (JSON 그대로) */
    public record ReviewDetail(Review review, List<FindingRepository.StoredFinding> findings,
                               @JsonRawValue String context) {
    }
}
