package com.prguard.conflicts;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 겹침은 협업 위험 신호이며 실제 병합 실패나 실행 오류의 증명이 아니다. */
public record ConflictReport(String status, Instant checkedAt, List<Pair> conflicts, List<Cluster> clusters,
                             Map<Integer, String> heads, List<String> notes) {
    public record Pr(int number, String title, String author, String url) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Overlap(String filename, @JsonProperty("overlapping_ranges") List<List<Integer>> ranges) { }
    public record Pair(@JsonProperty("pr_a") Pr a, @JsonProperty("pr_b") Pr b,
                       @JsonProperty("conflicting_files") List<Overlap> files, boolean verified) { }
    public record Cluster(List<Pr> prs, List<Pair> conflicts,
                          @JsonProperty("shared_files") List<String> sharedFiles) { }
    public record EngineResult(int version, List<Pair> conflicts, List<Cluster> clusters) { }
}
