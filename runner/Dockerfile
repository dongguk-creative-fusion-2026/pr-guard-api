# 실행 검증 러너: 대상 레포의 커밋 하나를 받아 빌드 · 테스트하고 결과를 PR Guard API 로 보낸다
FROM eclipse-temurin:21-jdk-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && (userdel -r ubuntu 2>/dev/null || true) \
    && useradd --uid 1000 --create-home runner
COPY --chmod=755 run-tests.sh /usr/local/bin/run-tests
USER 1000:1000
WORKDIR /home/runner
ENTRYPOINT ["run-tests"]
