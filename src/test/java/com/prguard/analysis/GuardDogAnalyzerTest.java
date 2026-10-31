package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.diff.Patch;
import static com.prguard.analysis.GuardDogReport.*;
import com.prguard.workspace.Checkout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuardDogAnalyzerTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper();
    final GuardDogClient client = mock(GuardDogClient.class);

    static GuardDogProperties props(boolean enabled, int max) {
        return new GuardDogProperties(enabled, "guarddog", Duration.ofSeconds(5), Duration.ofSeconds(30),
                max, 1024, true);
    }

    AnalysisContext context(ChangedFile... files) throws Exception {
        Path base = Files.createDirectories(temp.resolve("base"));
        Path head = Files.createDirectories(temp.resolve("head"));
        AnalysisContext ctx = new AnalysisContext(null, List.of(files));
        ctx.setCheckout(new Checkout(temp, base, head, "base", "head"));
        return ctx;
    }

    void write(String side, String path, String value) throws Exception {
        Path file = temp.resolve(side).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    static ChangedFile file(String path) {
        return new ChangedFile(path, "modified", 1, 0, null, null, Patch.EMPTY);
    }

    void risk(String ecosystem, String name, String version) throws Exception {
        when(client.scan(eq(ecosystem), eq(name), eq(version), any())).thenReturn(mapper.readTree("""
                {"issues":2,"errors":{},"results":{},"risk_score":{"score":8.0},
                 "risks":[{"severity":"high","threat_rule":"exfiltrate-sensitive-data",
                           "threat_description":"Network exfiltration"}]}
                """));
    }

    @Test void onlyAddedOrChangedDependenciesBecomeSecurityFindingsWithoutJavaIndex() throws Exception {
        var ctx = context(file("package.json"));
        write("base", "package.json", """
                {"dependencies":{"unchanged":"1.0.0","changed":"1.0.0","removed":"1.0.0"}}
                """);
        write("head", "package.json", """
                {"dependencies":{"unchanged":"1.0.0","changed":"2.0.0"},"devDependencies":{"new":"3.0.0"}}
                """);
        risk("npm", "changed", "2.0.0");
        risk("npm", "new", "3.0.0");
        var result = new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(ctx.indexed()).isFalse();
        assertThat(result).hasSize(2).allSatisfy(f -> {
            assertThat(f.category()).isEqualTo(Category.SECURITY);
            assertThat(f.severity()).isEqualTo(Severity.MAJOR);
            assertThat(f.source()).isEqualTo(Finding.TOOL);
            assertThat(f.file()).isEqualTo("package.json");
            assertThat(f.line()).isNull();
            assertThat(f.evidence()).contains("exfiltrate-sensitive-data");
        });
        verify(client, times(2)).scan(any(), any(), any(), any());
        assertThat(ctx.guarddog().status()).isEqualTo(Status.COMPLETED);
        assertThat(ctx.guarddog().counts().completed()).isEqualTo(2);
    }

    @Test void lockOnlyChangeScansExactDirectVersionAndDoesNotDuplicateManifest() throws Exception {
        var ctx = context(file("package-lock.json"), file("package.json"));
        for (String side : List.of("base", "head")) {
            write(side, "package.json", "{\"dependencies\":{\"@scope/pkg\":\"^1.0.0\"}}");
            String version = side.equals("base") ? "1.0.1" : "1.0.2";
            write(side, "package-lock.json", """
                    {"lockfileVersion":3,"packages":{"":{"dependencies":{"@scope/pkg":"^1.0.0"}},
                    "node_modules/@scope/pkg":{"version":"%s"}}}
                    """.formatted(version));
        }
        risk("npm", "@scope/pkg", "1.0.2");
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).hasSize(1);
        verify(client, times(1)).scan(eq("npm"), eq("@scope/pkg"), eq("1.0.2"), any());
    }

    @Test void renamesAndNormalizedPypiNamesDoNotRescanExistingDependency() throws Exception {
        var renamed = new ChangedFile("new/requirements.txt", "renamed", 0, 0, null,
                "old/requirements.txt", Patch.EMPTY);
        var ctx = context(renamed);
        write("base", "old/requirements.txt", "Some_Package==1.0.0\n");
        write("head", "new/requirements.txt", "some-package[extra]==1.0.0; python_version >= '3.10'\n");
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.NO_CHANGES);
        verifyNoInteractions(client);
    }

    @Test void failuresAndPartialErrorsAreRecordedAndOtherPackagesContinue() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "missing==1.0.0\npartial==2.0.0\nclean==3.0.0\n");
        when(client.scan(eq("pypi"), eq("missing"), any(), any())).thenThrow(new IllegalStateException("timeout"));
        when(client.scan(eq("pypi"), eq("partial"), any(), any())).thenReturn(mapper.readTree("""
                {"issues":0,"errors":{"download-package":"failed"}}
                """));
        when(client.scan(eq("pypi"), eq("clean"), any(), any())).thenReturn(mapper.readTree("""
                {"issues":0,"errors":{},"risks":[]}
                """));
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.PARTIAL);
        assertThat(ctx.guarddog().counts().failed()).isEqualTo(2);
        assertThat(ctx.guarddog().counts().completed()).isEqualTo(1);
        assertThat(ctx.guarddog().packages().get(1).problems().getFirst().code()).isEqualTo(Reason.DOWNLOAD_FAILED);
        verify(client, times(3)).scan(any(), any(), any(), any());
    }

    @Test void packageLimitAndUnsupportedVersionsAreVisible() throws Exception {
        var ctx = context(file("package.json"));
        write("head", "package.json", """
                {"dependencies":{"range":"^1.0.0","url":"https://example.com/pkg.tgz","one":"1.0.0","two":"2.0.0"}}
                """);
        risk("npm", "one", "1.0.0");
        assertThat(new GuardDogAnalyzer(props(true, 1), client, mapper).analyze(ctx)).hasSize(1);
        assertThat(ctx.notes()).anyMatch(n -> n.contains("지원하지 않는"))
                .anyMatch(n -> n.contains("한도 초과"));
        verify(client, times(1)).scan(any(), any(), any(), any());
    }

    @Test void mediumRiskIsMinorAndSamePackageAcrossFilesIsScannedOnce() throws Exception {
        var ctx = context(file("a/requirements.txt"), file("b/requirements.txt"));
        write("head", "a/requirements.txt", "pkg==1.0.0");
        write("head", "b/requirements.txt", "pkg==1.0.0");
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("""
                {"issues":1,"errors":{},"risks":[{"severity":"medium","threat_rule":"metadata-rule"}]}
                """));
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).hasSize(2)
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(Severity.MINOR));
        assertThat(ctx.guarddog().counts().total()).isEqualTo(1);
        assertThat(ctx.guarddog().packages().getFirst().files()).containsExactly("a/requirements.txt", "b/requirements.txt");
        verify(client, times(1)).scan(any(), any(), any(), any());
    }

    @Test void disabledOrMissingCheckoutNeverStartsScanner() {
        var ctx = new AnalysisContext(null, List.of(file("package.json")));
        assertThat(new GuardDogAnalyzer(props(false, 20), client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.DISABLED);
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
        assertThat(ctx.notes()).anyMatch(n -> n.contains("꺼짐")).anyMatch(n -> n.contains("소스가 없어"));
        verifyNoInteractions(client);
    }

    @Test void unchangedUnsupportedDeclarationsDoNotMakeANewDependencyScanIncomplete() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("base", "requirements.txt", "-r shared.txt\n");
        write("head", "requirements.txt", "-r shared.txt\npkg==1.0.0\n");
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"issues\":0,\"errors\":{},\"risks\":[]}"));
        assertThat(new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.COMPLETED);
        assertThat(ctx.guarddog().counts().total()).isEqualTo(1);
    }

    @Test void unsupportedOnlyIsIncompleteAndDoesNotExposeUrlCredentials() throws Exception {
        var ctx = context(file("package.json"));
        write("head", "package.json", """
                {"dependencies":{"pkg":"https://user:private-password@example.invalid/a.tgz?token=private-token"}}
                """);
        new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
        assertThat(ctx.guarddog().packages()).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("pkg");
            assertThat(p.version()).isNull();
            assertThat(p.status()).isEqualTo(PackageStatus.SKIPPED);
            assertThat(p.problems().getFirst().code()).isEqualTo(Reason.UNSUPPORTED_SPEC);
        });
        assertThat(mapper.writeValueAsString(ctx.guarddog())).doesNotContain("private-password", "private-token", "https://");
        verifyNoInteractions(client);
    }

    @Test void partialResultsRetainRealRisksWithoutExposingScannerErrors() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "pkg==1.0.0");
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("""
                {"issues":1,"errors":{"metadata-rule":"private-diagnostic-value"},
                 "risks":[{"severity":"high","threat_rule":"risk-rule","threat_description":"source evidence"}]}
                """));
        var result = new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(result).singleElement().satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.MAJOR));
        assertThat(ctx.guarddog().status()).isEqualTo(Status.PARTIAL);
        assertThat(ctx.guarddog().packages().getFirst().status()).isEqualTo(PackageStatus.PARTIAL);
        assertThat(ctx.guarddog().packages().getFirst().problems().getFirst().code()).isEqualTo(Reason.SCANNER_ERRORS);
        assertThat(mapper.writeValueAsString(ctx.guarddog())).doesNotContain("private-diagnostic-value");
    }

    @Test void malformedRiskIsNotACleanScanAndWrongVersionCannotCreateFinding() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "pkg==1.0.0");
        var analyzer = new GuardDogAnalyzer(props(true, 20), client, mapper);
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"issues\":1,\"errors\":{},\"risks\":[\"invalid\"]}"));
        assertThat(analyzer.analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("""
                {"issues":1,"errors":{},"package_version":"9.0.0","risks":[{"severity":"high","threat_rule":"risk"}]}
                """));
        assertThat(analyzer.analyze(ctx)).isEmpty();
        assertThat(ctx.guarddog().packages().getFirst().problems().getFirst().code()).isEqualTo(Reason.MALFORMED_OUTPUT);
    }

    @Test void malformedManifestIsReportedEvenWhenNoPackageCouldBeCollected() throws Exception {
        var ctx = context(file("package.json"));
        write("head", "package.json", "{ invalid private-manifest-content");
        new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
        assertThat(ctx.guarddog().counts().total()).isZero();
        assertThat(ctx.guarddog().problems()).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo(Reason.MANIFEST_ERROR);
            assertThat(p.file()).isEqualTo("package.json");
        });
        assertThat(mapper.writeValueAsString(ctx.guarddog())).doesNotContain("private-manifest-content");
        verifyNoInteractions(client);
    }

    @Test void missingSourceDoesNotFailAnUnrelatedCodeOnlyChange() {
        var ctx = new AnalysisContext(null, List.of(file("src/App.java")));
        new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.NO_CHANGES);
        assertThat(ctx.guarddog().requiresAttention()).isFalse();
        verifyNoInteractions(client);
    }

    @Test void scanLimitStillReportsEveryCollectedPackage() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "one==1.0.0\ntwo==1.0.0\nthree==1.0.0");
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"issues\":0,\"errors\":{},\"risks\":[]}"));
        new GuardDogAnalyzer(props(true, 1), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.PARTIAL);
        assertThat(ctx.guarddog().counts().total()).isEqualTo(3);
        assertThat(ctx.guarddog().counts().skipped()).isEqualTo(2);
        assertThat(ctx.guarddog().packages().get(2).problems().getFirst().code()).isEqualTo(Reason.PACKAGE_LIMIT);
        verify(client, times(1)).scan(any(), any(), any(), any());
    }

    @Test void interruptedScanMarksRemainingPackagesInsteadOfDroppingThem() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "one==1.0.0\ntwo==1.0.0");
        when(client.scan(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new GuardDogException(Reason.INTERRUPTED, "GuardDog 실행 중단");
        });
        try {
            new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
            assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
            assertThat(ctx.guarddog().counts().failed()).isEqualTo(1);
            assertThat(ctx.guarddog().counts().skipped()).isEqualTo(1);
            assertThat(ctx.guarddog().packages().get(1).problems().getFirst().code()).isEqualTo(Reason.INTERRUPTED);
            verify(client, times(1)).scan(any(), any(), any(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void removingLockfileMakesPreviouslyResolvedRangeIncomplete() throws Exception {
        var removed = new ChangedFile("package-lock.json", "removed", 0, 1, null, null, Patch.EMPTY);
        var ctx = context(removed);
        for (String side : List.of("base", "head")) {
            write(side, "package.json", "{\"dependencies\":{\"pkg\":\"^1.0.0\"}}");
        }
        write("base", "package-lock.json", """
                {"lockfileVersion":3,"packages":{"":{"dependencies":{"pkg":"^1.0.0"}},"node_modules/pkg":{"version":"1.2.0"}}}
                """);
        new GuardDogAnalyzer(props(true, 20), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.FAILED);
        assertThat(ctx.guarddog().packages().getFirst().problems().getFirst().code()).isEqualTo(Reason.UNSUPPORTED_SPEC);
        verifyNoInteractions(client);
    }

    @Test void discoveryLimitCannotHideUncollectedTargetsBehindSuccessfulCoverage() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> "pkg" + i + "==1.0.0").collect(java.util.stream.Collectors.joining("\n")));
        when(client.scan(any(), any(), any(), any())).thenReturn(mapper.readTree("{\"issues\":0,\"errors\":{},\"risks\":[]}"));
        new GuardDogAnalyzer(props(true, 1), client, mapper).analyze(ctx);
        assertThat(ctx.guarddog().status()).isEqualTo(Status.PARTIAL);
        assertThat(ctx.guarddog().counts().total()).isEqualTo(500);
        assertThat(ctx.guarddog().problems()).anySatisfy(p -> assertThat(p.code()).isEqualTo(Reason.DISCOVERY_LIMIT));
        verify(client, times(1)).scan(any(), any(), any(), any());
    }

    @Test void expiredTotalBudgetSkipsRemainingPackages() throws Exception {
        var ctx = context(file("requirements.txt"));
        write("head", "requirements.txt", "pkg==1.0.0");
        var settings = new GuardDogProperties(true, "guarddog", Duration.ofSeconds(1),
                Duration.ofNanos(1), 20, 1024, true);
        assertThat(new GuardDogAnalyzer(settings, client, mapper).analyze(ctx)).isEmpty();
        assertThat(ctx.notes()).anyMatch(n -> n.contains("한도 초과"));
        verifyNoInteractions(client);
    }

    @Test void configurationDefaultsKeepIntegrationDisabled() {
        var runner = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class);
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var settings = context.getBean(GuardDogProperties.class);
            assertThat(settings.enabled()).isFalse();
            assertThat(settings.executable()).isEqualTo("guarddog");
            assertThat(settings.timeout()).isEqualTo(Duration.ofMinutes(1));
            assertThat(settings.includeDevDependencies()).isTrue();
        });
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(GuardDogProperties.class)
    static class PropertiesConfig { }
}
