package com.prguard.conflicts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.pr-conflicts")
public record ConflictProperties(Boolean enabled, String python, Duration timeout, int maxPulls) {
    public ConflictProperties {
        enabled = enabled == null || enabled;
        python = python == null || python.isBlank() ? "python3" : python;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        maxPulls = maxPulls <= 0 ? 30 : Math.min(maxPulls, 100);
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("PR conflict timeout must be positive");
        }
    }
}
