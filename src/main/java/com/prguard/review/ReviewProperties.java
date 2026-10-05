package com.prguard.review;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.review")
public record ReviewProperties(Duration workerDelay, int maxPatchChars, Duration staleAfter) {
}
