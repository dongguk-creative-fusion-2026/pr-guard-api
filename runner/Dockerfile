# 실행 검증 러너: 대상 레포의 커밋 하나를 받아 빌드 · 테스트하고 결과를 PR Guard API 로 보낸다

# 호출 기록 에이전트 (trace-agent/)
FROM gradle:8.14-jdk21 AS agent
WORKDIR /agent
COPY trace-agent/ .
RUN gradle shadowJar --no-daemon -q

FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && (userdel -r ubuntu 2>/dev/null || true) \
    && useradd --uid 1000 --create-home runner
COPY --from=agent /agent/build/libs/trace-agent.jar /opt/prguard/trace-agent.jar
COPY --chmod=755 run-tests.sh /usr/local/bin/run-tests
USER 1000:1000
WORKDIR /home/runner
ENTRYPOINT ["run-tests"]
