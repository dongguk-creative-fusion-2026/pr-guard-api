package com.prguard.review;

import com.prguard.common.ApiException;
import com.prguard.project.ProjectService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ProjectService projects;
    private final ReviewRepository reviews;

    public ReviewController(ProjectService projects, ReviewRepository reviews) {
        this.projects = projects;
        this.reviews = reviews;
    }

    @GetMapping("/api/projects/{id}/reviews")
    public List<Review> list(@PathVariable long id, @RequestParam(defaultValue = "20") int limit) {
        projects.get(id);
        return reviews.findByProject(id, Math.clamp(limit, 1, 100));
    }

    @GetMapping("/api/reviews/{id}")
    public Review get(@PathVariable long id) {
        return reviews.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "리뷰가 없습니다: " + id));
    }
}
