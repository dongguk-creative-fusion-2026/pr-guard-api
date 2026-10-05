package com.prguard.pipeline;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prguard.poll")
public record PollProperties(Duration interval) {
}
