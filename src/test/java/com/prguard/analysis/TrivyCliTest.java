package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.trivy.*;
import com.prguard.workspace.Checkout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** 명시적으로 실행할 때만 실제 CLI/공개 취약점 DB 를 사용하는 통합 테스트. */
@EnabledIfEnvironmentVariable(named = "TRIVY_TEST_BINARY", matches = ".+")
class TrivyCliTest {
    @TempDir Path temp;

    @Test
    void detectsNewLog4ShellAndRootUserWithActualTrivy() throws Exception {
        Path base = Files.createDirectory(temp.resolve("base"));
        Path head = Files.createDirectory(temp.resolve("head"));
        Files.writeString(base.resolve("Dockerfile"), "FROM alpine:3.22\nUSER 1000\nCMD [\"true\"]\n");
        Files.writeString(head.resolve("Dockerfile"), "FROM alpine:3.22\nUSER root\nCMD [\"true\"]\n");
        Files.writeString(base.resolve("gradle.lockfile"), "org.apache.logging.log4j:log4j-core:2.25.3=runtimeClasspath\nempty=\n");
        Files.writeString(head.resolve("gradle.lockfile"), "org.apache.logging.log4j:log4j-core:2.14.1=runtimeClasspath\nempty=\n");
        // 대상 레포가 검사 설정/ignore 를 바꿔도 외부 분석기의 정책은 바뀌지 않는다.
        Files.writeString(head.resolve("trivy.yaml"), "severity: [UNKNOWN]\nscanners: []\n");
        Files.writeString(head.resolve(".trivyignore"), "CVE-2021-44228\nDS-0002\n");
        var props = new TrivyProperties(true, System.getenv("TRIVY_TEST_BINARY"), temp.resolve("cache"), null, null);
        var scanner = new TrivyScanner(props, new TrivyProcess(), new TrivyReportParser(new ObjectMapper()));
        var ctx = new AnalysisContext(null, List.of());
        ctx.setCheckout(new Checkout(base, base, head, "base", "head"));
        var findings = new TrivyAnalyzer(props, scanner).analyze(ctx);
        assertThat(findings).anyMatch(f -> f.ruleId().equals("TRIVY_VULNERABILITY")
                && f.title().contains("CVE-2021-44228") && f.file().equals("gradle.lockfile"));
        assertThat(findings).anyMatch(f -> f.ruleId().equals("TRIVY_MISCONFIGURATION")
                && f.title().contains("DS-0002") && f.line() == 2);
        assertThat(findings).allMatch(f -> f.severity() == Severity.MAJOR);
    }
}
