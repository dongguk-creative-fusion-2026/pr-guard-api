package com.prguard.analysis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties("prguard.guarddog")
public record GuardDogProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("guarddog") @NotBlank String executable,
        @DefaultValue("PT1M") Duration timeout,
        @DefaultValue("PT3M") Duration totalTimeout,
        @DefaultValue("20") @Min(1) int maxPackages,
        @DefaultValue("1048576") @Min(1024) int maxOutputBytes,
        @DefaultValue("true") boolean includeDevDependencies) {

    public GuardDogProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()) {
            throw new IllegalArgumentException("GuardDog timeout must be positive");
        }
    }
}
