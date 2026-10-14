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
# 레포가 툴체인으로 Java 17 을 요구하는 경우가 많다 (Spring Boot 3 기본). 기본 JDK 21 옆에 17 을 둔다
COPY --from=eclipse-temurin:17-jdk-noble /opt/java/openjdk /opt/java/17
# 변경분 변이 테스트: PIT 명령줄 실행기 (빌드 도구 플러그인 없이 레포의 테스트 클래스패스로 돌린다)
RUN mkdir -p /opt/prguard/pit && cd /opt/prguard/pit \
    && for j in org/pitest/pitest/1.17.0/pitest-1.17.0.jar \
                org/pitest/pitest-entry/1.17.0/pitest-entry-1.17.0.jar \
                org/pitest/pitest-command-line/1.17.0/pitest-command-line-1.17.0.jar \
                org/pitest/pitest-junit5-plugin/1.2.1/pitest-junit5-plugin-1.2.1.jar \
                org/apache/commons/commons-text/1.12.0/commons-text-1.12.0.jar \
                org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar; do \
         curl -fsSLO "https://repo1.maven.org/maven2/$j"; \
       done
COPY --chmod=755 run-tests.sh /usr/local/bin/run-tests
USER 1000:1000
WORKDIR /home/runner
ENTRYPOINT ["run-tests"]
