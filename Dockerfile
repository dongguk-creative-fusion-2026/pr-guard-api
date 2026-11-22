FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon > /dev/null || true
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-jammy
ARG GUARDDOG_VERSION=3.2.0
ARG SEMGREP_VERSION=1.180.0
# Use separate environments so both scanners keep their own Python dependencies.
RUN apt-get update && apt-get install -y --no-install-recommends git python3 python3-venv \
    && python3 -m venv /opt/guarddog \
    && /opt/guarddog/bin/pip install --no-cache-dir "guarddog==${GUARDDOG_VERSION}" \
    && python3 -m venv /opt/semgrep \
    && /opt/semgrep/bin/pip install --no-cache-dir "semgrep==${SEMGREP_VERSION}" \
    && /opt/guarddog/bin/python -c 'import shutil, sysconfig; shutil.copytree(sysconfig.get_path("purelib") + "/guarddog/analyzer/metadata/resources", "/var/cache/prguard/guarddog", dirs_exist_ok=True)' \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --create-home --gid app app \
    && chown -R app:app /var/cache/prguard/guarddog
ENV PATH="/opt/guarddog/bin:/opt/semgrep/bin:${PATH}"
# The production image is ready to scan; an explicit runtime setting can opt out.
ENV GUARDDOG_ENABLED=true
ENV GUARDDOG_EXECUTABLE=/opt/guarddog/bin/guarddog
ENV GUARDDOG_TOP_PACKAGES_CACHE_LOCATION=/var/cache/prguard/guarddog
ENV SEMGREP_BIN=/opt/semgrep/bin/semgrep
ENV SEMGREP_SEND_METRICS=off SEMGREP_ENABLE_VERSION_CHECK=0
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "app.jar"]
