FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon > /dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# 분석할 레포를 clone 한다. Semgrep 검사(C. 보안)는 바뀐 파일을 semgrep CLI 로 돌린다
RUN apk add --no-cache git python3 py3-pip \
    && pip install --no-cache-dir --break-system-packages semgrep==1.180.0 \
    && addgroup -S app && adduser -S app -G app
ENV SEMGREP_SEND_METRICS=off SEMGREP_ENABLE_VERSION_CHECK=0
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
