FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon > /dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

# 레포 그래프용 GitNexus. 네이티브 모듈(tree-sitter, LadybugDB)이 glibc 용이라 런타임도 alpine 이 아닌 이미지를 쓴다
FROM node:24-bookworm-slim AS graph
WORKDIR /graph
COPY gitnexus/package.json gitnexus/package-lock.json ./
# 임베딩(시맨틱 검색) 런타임과 다른 OS 용 네이티브 빌드는 쓰지 않아서 지운다 (이미지 약 0.8GB 절약)
RUN npm ci --omit=dev --no-audit --no-fund \
    && rm -rf node_modules/onnxruntime-node node_modules/onnxruntime-web \
    && find node_modules -type d -path '*/prebuilds/*' ! -name 'linux-x64' -prune -exec rm -rf {} +
COPY gitnexus/export.mjs ./

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
# 분석할 레포를 clone 한다
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --create-home app
COPY --from=graph /usr/local/bin/node /usr/local/bin/node
COPY --from=graph /graph /app/gitnexus
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app
ENV GRAPH_SCRIPT=/app/gitnexus/export.mjs
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
