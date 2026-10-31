package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Network-enabled, opt-in tests. Never download or execute known malware samples. */
class GuardDogLiveCliTest {
    private final ObjectMapper mapper = new ObjectMapper();

    static GuardDogProperties settings() {
        String executable = System.getenv().getOrDefault("GUARDDOG_EXECUTABLE", "guarddog");
        return new GuardDogProperties(true, executable, Duration.ofMinutes(2), Duration.ofMinutes(5),
                10, 1_048_576, true);
    }

    @Test void realNpmRegistryScanReturnsV3Results() throws Exception {
        JsonNode result = new GuardDogCliClient(settings(), mapper)
                .scan("npm", "is-number", "7.0.0", Duration.ofMinutes(2));
        assertComplete(result);
        assertThat(result.path("package_version").asText()).isEqualTo("7.0.0");
        report("npm-is-number-7.0.0.json", result);
    }

    @Test void realPypiRegistryScanReturnsV3Results() throws Exception {
        JsonNode result = new GuardDogCliClient(settings(), mapper)
                .scan("pypi", "six", "1.17.0", Duration.ofMinutes(2));
        assertComplete(result);
        assertThat(result.path("package_version").asText()).isEqualTo("1.17.0");
        report("pypi-six-1.17.0.json", result);
    }

    @Test void missingPackageIsReportedAsAnError() throws Exception {
        String name = "prguard-verification-missing-" + UUID.randomUUID().toString().replace("-", "");
        JsonNode result = new GuardDogCliClient(settings(), mapper)
                .scan("pypi", name, "1.0.0", Duration.ofMinutes(2));
        assertThat(result.path("errors").isEmpty()).as("missing package must not look like a successful scan").isFalse();
        report("pypi-missing-package.json", result);
    }

    private static void assertComplete(JsonNode result) {
        assertThat(result.path("errors").isObject()).isTrue();
        assertThat(result.path("errors").isEmpty()).as(result.toPrettyString()).isTrue();
        assertThat(result.path("risks").isArray()).as("GuardDog v3 risks schema").isTrue();
        assertThat(result.path("issues").isIntegralNumber()).isTrue();
    }

    static void report(String name, JsonNode result) throws Exception {
        Path directory = Path.of("build/reports/guarddog");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), result.toPrettyString());
    }
}
