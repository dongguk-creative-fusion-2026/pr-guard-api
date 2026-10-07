package com.prguard.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.common.ApiException;
import com.prguard.execution.JUnitReportParser.TestCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 러너(runner/run-tests.sh)가 진행과 결과를 보내는 곳. 실행마다 따로 만든 토큰(X-Run-Token)으로만 받는다.
 */
@RestController
public class TestRunController {

    /** 결과에 남길 테스트 케이스 수 상한 (큰 레포에서 행이 너무 커지지 않게) */
    private static final int MAX_CASES = 5000;
    private static final int MAX_LOG = 20_000;

    private final TestRunRepository runs;
    private final ObjectMapper mapper;

    public TestRunController(TestRunRepository runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    /** 리뷰의 실행 기록 (화면의 실행 검증 상세) */
    @GetMapping("/api/reviews/{reviewId}/test-runs")
    public List<TestRun> list(@PathVariable long reviewId) {
        return runs.findByReview(reviewId);
    }

    /** @param phase cloning, building … */
    public record Progress(String phase, String message) {
    }

    @PostMapping("/api/test-runs/{id}/progress")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void progress(@PathVariable long id, @RequestHeader("X-Run-Token") String token, @RequestBody Progress body) {
        authorize(id, token);
        runs.progress(id, limit(body.phase(), 40), limit(body.message(), 200));
    }

    /**
     * 빌드 · 테스트가 끝난 뒤 한 번 보낸다.
     *
     * @param exitCode 빌드 도구 종료 코드 (테스트 실패도 0 이 아니다)
     * @param report   JUnit XML 보고서들 (TEST-*.xml). 하나도 없으면 빌드 실패로 본다
     * @param log      빌드 출력 끝부분
     */
    @PostMapping("/api/test-runs/{id}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@PathVariable long id, @RequestHeader("X-Run-Token") String token,
                       @RequestParam("exitCode") int exitCode,
                       @RequestParam(value = "report", required = false) List<MultipartFile> report,
                       @RequestParam(value = "log", required = false) MultipartFile log,
                       @RequestParam(value = "error", required = false) String error) throws IOException {
        authorize(id, token);
        String logTail = log == null ? null : tail(new String(log.getBytes(), StandardCharsets.UTF_8));
        if (report == null || report.isEmpty()) {
            runs.fail(id, error != null && !error.isBlank() ? limit(error, 300)
                    : "테스트 보고서가 없음 (빌드 실패 · 종료 코드 " + exitCode + ")", exitCode, logTail);
            return;
        }
        List<TestCase> cases = new ArrayList<>();
        for (MultipartFile file : report) {
            try (InputStream in = file.getInputStream()) {
                cases.addAll(JUnitReportParser.parse(in));
            } catch (ExecutionException e) {
                // 깨진 보고서 하나 때문에 전체를 버리지 않는다
            }
        }
        int failures = (int) cases.stream().filter(c -> c.status() == JUnitReportParser.Status.FAILED).count();
        int errors = (int) cases.stream().filter(c -> c.status() == JUnitReportParser.Status.ERROR).count();
        int skipped = (int) cases.stream().filter(c -> c.status() == JUnitReportParser.Status.SKIPPED).count();
        runs.complete(id, exitCode, cases.size(), failures, errors, skipped,
                json(cases.size() > MAX_CASES ? cases.subList(0, MAX_CASES) : cases), logTail);
    }

    private void authorize(long id, String token) {
        TestRun run = runs.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEST_RUN_NOT_FOUND", "실행 기록이 없습니다: " + id));
        byte[] expected = run.tokenHash().getBytes(StandardCharsets.UTF_8);
        byte[] actual = TestRunService.sha256(token == null ? "" : token).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TEST_RUN_TOKEN", "실행 토큰이 맞지 않습니다");
        }
        if (run.finished()) {
            throw new ApiException(HttpStatus.CONFLICT, "TEST_RUN_FINISHED", "이미 끝난 실행입니다: " + id);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String tail(String text) {
        return text.length() > MAX_LOG ? text.substring(text.length() - MAX_LOG) : text;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
