package com.prguard.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.review.OpenAiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiEvidenceGeneratorTest {

    private final OpenAiEvidenceGenerator generator = new OpenAiEvidenceGenerator(RestClient.builder(),
            new OpenAiProperties("http://localhost", "k", "m"), new ObjectMapper());

    private static EvidenceRequest request() {
        return new EvidenceRequest("o/r", 4, "t", null, List.of(new EvidenceRequest.Target("a.b.Foo#bar(Long)",
                "src/main/java/a/b/Foo.java", "a.b.PrGuardEvidence1Test",
                "src/test/java/a/b/PrGuardEvidence1Test.java", "", "", "", null, List.of("junit-jupiter"))));
    }

    @Test
    void parse_keepsOnlyTestsForRequestedTargetsWithExpectedClass() {
        String json = """
                {"tests":[
                  {"target":"a.b.Foo#bar(Long)","intent":"없는 id 면 예외 대신 null",
                   "code":"package a.b;\\nclass PrGuardEvidence1Test { }"},
                  {"target":"a.b.Foo#other()","intent":"","code":"package a.b;\\nclass PrGuardEvidence2Test { }"},
                  {"target":"a.b.Foo#bar(Long)","intent":"","code":"package x;\\nclass Wrong { }"}
                ]}""";

        List<EvidenceTest> tests = generator.parse(json, request());

        assertThat(tests).hasSize(1);
        assertThat(tests.get(0).path()).isEqualTo("src/test/java/a/b/PrGuardEvidence1Test.java");
        assertThat(tests.get(0).intent()).isEqualTo("없는 id 면 예외 대신 null");
    }

    @Test
    void fixtureClassName_comesFromTestPath() {
        assertThat(FixtureEvidenceGenerator.className("mod/src/test/java/a/b/PrGuardEvidence1Test.java"))
                .isEqualTo("a.b.PrGuardEvidence1Test");
    }
}
