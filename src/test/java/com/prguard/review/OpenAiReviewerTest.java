package com.prguard.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiReviewerTest {

    private final OpenAiReviewer reviewer = new OpenAiReviewer(RestClient.builder(),
            new OpenAiProperties("http://localhost", "k", "m"), new ReviewPrompt(1000), new ObjectMapper());

    @Test
    void parse_readsKindAndSymbols_unknownKindBecomesOther_blankSymbolsDropped() {
        String json = """
                {"summary":"s","findings":[
                  {"severity":"BLOCKER","file":"A.java","line":3,"title":"t","message":"m",
                   "kind":"MISSING_SYMBOL","symbols":["Post#getUpdatedAt()","  ",""]},
                  {"severity":"MAJOR","file":"B.java","line":null,"title":"t","message":"m",
                   "kind":"SOMETHING_ELSE","symbols":[]}
                ]}""";

        LlmReview review = reviewer.parse(json);

        assertThat(review.findings().get(0).kind()).isEqualTo("MISSING_SYMBOL");
        assertThat(review.findings().get(0).symbols()).containsExactly("Post#getUpdatedAt()");
        assertThat(review.findings().get(1).kind()).isEqualTo("OTHER");
        assertThat(review.findings().get(1).symbols()).isEmpty();
    }
}
