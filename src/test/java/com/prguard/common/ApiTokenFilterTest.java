package com.prguard.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiTokenFilterTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private MockHttpServletResponse run(String token, String method, String path, String header) throws Exception {
        ApiTokenFilter filter = new ApiTokenFilter(new ApiTokenProperties(token), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (header != null) {
            request.addHeader(ApiTokenFilter.HEADER, header);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // 통과하면 체인이 요청을 받는다
        if (chain.getRequest() == null) {
            assertThat(response.getStatus()).isEqualTo(401);
        }
        return response;
    }

    private boolean passes(String token, String method, String path, String header) throws Exception {
        return run(token, method, path, header).getStatus() == 200;
    }

    @Test
    void withToken_requiresMatchingHeaderOnApi() throws Exception {
        assertThat(passes("t0k", "GET", "/api/projects", "t0k")).isTrue();
        assertThat(passes("t0k", "DELETE", "/api/projects/1", "t0k")).isTrue();

        assertThat(passes("t0k", "GET", "/api/projects", null)).isFalse();
        assertThat(passes("t0k", "DELETE", "/api/projects/1", "wrong")).isFalse();
        assertThat(passes("t0k", "POST", "/api/projects/1/agents-md/pull", "t0")).isFalse();
        assertThat(run("t0k", "GET", "/api/projects", null).getContentAsString()).contains("API_TOKEN_INVALID");
    }

    @Test
    void callbacksWithTheirOwnAuth_andNonApi_areNotChecked() throws Exception {
        assertThat(passes("t0k", "POST", "/api/github/webhook", null)).isTrue();
        assertThat(passes("t0k", "POST", "/api/test-runs/5/report", null)).isTrue();
        assertThat(passes("t0k", "GET", "/api/test-runs/5/extra-files", null)).isTrue();
        assertThat(passes("t0k", "POST", "/api/projects/3/graph/progress", null)).isTrue();
        assertThat(passes("t0k", "POST", "/api/projects/3/graph/result", null)).isTrue();
        assertThat(passes("t0k", "GET", "/actuator/health", null)).isTrue();

        // 그래프 다시 만들기 · 조회는 화면 기능이라 토큰이 필요하다
        assertThat(passes("t0k", "POST", "/api/projects/3/graph", null)).isFalse();
        assertThat(passes("t0k", "GET", "/api/projects/3/graph", null)).isFalse();
    }

    @Test
    void withoutToken_everythingPasses() throws Exception {
        assertThat(passes("", "DELETE", "/api/projects/1", null)).isTrue();
        assertThat(passes(null, "GET", "/api/projects", null)).isTrue();
    }
}
