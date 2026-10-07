package com.prguard.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prguard.review.OpenAiProperties;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EvidenceConfig {

    /** 픽스처 디렉터리가 있으면 그것, 아니면 OpenAI, 둘 다 없으면 만들지 않는다 */
    @Bean
    EvidenceService evidenceService(EvidenceProperties props, OpenAiProperties openai, RestClient.Builder builder,
                                    ObjectMapper mapper) {
        EvidenceGenerator generator = null;
        if (props.fixtureDir() != null && !props.fixtureDir().isBlank()) {
            generator = new FixtureEvidenceGenerator(Path.of(props.fixtureDir()));
        } else if (openai.enabled()) {
            generator = new OpenAiEvidenceGenerator(builder, openai, mapper);
        }
        return new EvidenceService(generator, props);
    }
}
