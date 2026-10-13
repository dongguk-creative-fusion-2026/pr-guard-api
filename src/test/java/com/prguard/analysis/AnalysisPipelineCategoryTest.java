package com.prguard.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnalysisPipelineCategoryTest {

    @Test
    void securityClaimBecomesSecurityCategory() {
        assertThat(AnalysisPipeline.categoryOf("SECURITY")).isEqualTo(Category.SECURITY);
    }

    @Test
    void otherClaimsStayGeneral() {
        assertThat(AnalysisPipeline.categoryOf("MISSING_CHECK")).isEqualTo(Category.GENERAL);
        assertThat(AnalysisPipeline.categoryOf(null)).isEqualTo(Category.GENERAL);
    }
}
