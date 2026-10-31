package com.prguard.trivy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.trivy")
public record TrivyProperties(Boolean enabled, String binary, Path cacheDir, Duration timeout,
                              List<String> severities) {
    public TrivyProperties {
        enabled = Boolean.TRUE.equals(enabled);
        binary = binary == null || binary.isBlank() ? "trivy" : binary;
        cacheDir = (cacheDir == null ? Path.of(System.getProperty("java.io.tmpdir"), "prguard-trivy")
                : cacheDir).toAbsolutePath().normalize();
        timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        if (timeout.toSeconds() < 1 || timeout.compareTo(Duration.ofMinutes(3)) > 0) {
            throw new IllegalArgumentException("Trivy timeout must be between 1 second and 3 minutes");
        }
        severities = severities == null || severities.isEmpty() ? List.of("HIGH", "CRITICAL")
                : severities.stream().map(s -> s.toUpperCase(Locale.ROOT)).distinct().toList();
        if (!Set.of("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL").containsAll(severities)) {
            throw new IllegalArgumentException("Invalid Trivy severity");
        }
    }
}
