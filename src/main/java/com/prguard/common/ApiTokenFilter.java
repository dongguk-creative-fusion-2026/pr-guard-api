package com.prguard.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api/** 는 X-Api-Token 이 맞아야 받는다 (API_TOKEN 이 있을 때).
 * 자기 방식으로 확인하는 콜백은 뺀다: GitHub 웹훅(HMAC 서명), 실행 검증 러너(X-Run-Token), 그래프 워크플로(runId 를 GitHub 에 확인).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Token";
    private static final Pattern OWN_AUTH = Pattern.compile(
            "^/api/(github/webhook|test-runs/\\d+/.*|projects/\\d+/graph/(progress|result)|sonar/(token|result))$");

    private final ApiTokenProperties props;
    private final ObjectMapper mapper;

    public ApiTokenFilter(ApiTokenProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !props.enabled() || !path.startsWith("/api/") || OWN_AUTH.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), ErrorBody.of("API_TOKEN_INVALID", "API 토큰이 없거나 맞지 않습니다"));
    }

    private boolean matches(String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(props.token().getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
