package com.prguard.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Structured scan coverage, independent of whether a package has risk findings. */
public record GuardDogReport(Status status, String scope, Counts counts, List<PackageResult> packages,
                             List<Problem> problems, long durationMs) {
    public enum Status {
        DISABLED("검사 꺼짐"), NO_CHANGES("지원 범위 내 새 의존성 없음"), COMPLETED("검사 완료"),
        PARTIAL("일부 검사 미완료"), FAILED("검사 미완료");

        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum PackageStatus { COMPLETED, PARTIAL, FAILED, SKIPPED }

    public enum Reason {
        SOURCE_UNAVAILABLE, MANIFEST_ERROR, UNSUPPORTED_SPEC, DISCOVERY_LIMIT,
        PACKAGE_LIMIT, TOTAL_TIMEOUT, TIMEOUT, INTERRUPTED, START_FAILED, EXIT_ERROR,
        OUTPUT_LIMIT, MALFORMED_OUTPUT, DOWNLOAD_FAILED, SCANNER_ERRORS, EXECUTION_FAILED
    }

    public record Problem(Reason code, String message, String file) { }
    public record Risk(String rule, String severity, String description) { }
    public record Counts(int total, int completed, int partial, int failed, int skipped, int risks) { }

    public record PackageResult(String ecosystem, String name, String version, List<String> files,
                                PackageStatus status, List<Risk> risks, List<Problem> problems) {
        public PackageResult {
            files = List.copyOf(files);
            risks = List.copyOf(risks);
            problems = List.copyOf(problems);
        }
    }

    public GuardDogReport {
        packages = List.copyOf(packages);
        problems = List.copyOf(problems);
    }

    @JsonProperty
    public boolean requiresAttention() {
        return status == Status.PARTIAL || status == Status.FAILED;
    }

    public static GuardDogReport empty(Status status, List<Problem> problems, long durationMs) {
        return create(status, List.of(), problems, durationMs);
    }

    public static GuardDogReport completed(List<PackageResult> packages, List<Problem> problems, long durationMs) {
        boolean incomplete = !problems.isEmpty() || packages.stream().anyMatch(p -> p.status() != PackageStatus.COMPLETED);
        boolean analyzed = packages.stream().anyMatch(p -> p.status() == PackageStatus.COMPLETED || p.status() == PackageStatus.PARTIAL);
        Status status = incomplete ? (analyzed ? Status.PARTIAL : Status.FAILED)
                : packages.isEmpty() ? Status.NO_CHANGES : Status.COMPLETED;
        return create(status, packages, problems, durationMs);
    }

    private static GuardDogReport create(Status status, List<PackageResult> packages, List<Problem> problems, long ms) {
        Counts counts = new Counts(packages.size(), count(packages, PackageStatus.COMPLETED),
                count(packages, PackageStatus.PARTIAL), count(packages, PackageStatus.FAILED),
                count(packages, PackageStatus.SKIPPED), packages.stream().mapToInt(p -> p.risks().size()).sum());
        return new GuardDogReport(status, "NEW_DIRECT_NPM_PYPI", counts, packages, problems, ms);
    }

    private static int count(List<PackageResult> packages, PackageStatus status) {
        return (int) packages.stream().filter(p -> p.status() == status).count();
    }
}
