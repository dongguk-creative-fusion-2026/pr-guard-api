package com.prguard.trivy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 셸/레포 빌드 스크립트 없이 CLI 만 실행한다. 서버 토큰과 대상 레포의 설정을 상속하지 않는다. */
@Component
public class TrivyProcess {
    private static final Set<String> ALLOWED_ENV = Set.of("PATH", "PATHEXT", "SYSTEMROOT", "WINDIR",
            "SYSTEMDRIVE", "TEMP", "TMP", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR");

    public void run(List<String> command, Path directory, Duration timeout, String step) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var environment = builder.environment();
            var inherited = new HashMap<>(environment);
            environment.clear();
            inherited.forEach((key, value) -> {
                if (ALLOWED_ENV.contains(key.toUpperCase(java.util.Locale.ROOT))) {
                    environment.put(key, value);
                }
            });
            environment.put("HOME", directory.toString());
            environment.put("USERPROFILE", directory.toString());
            environment.put("XDG_CONFIG_HOME", directory.toString());
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Trivy " + step + " 시간 초과 (" + timeout.toSeconds() + "s)");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Trivy " + step + " 실패 (exit=" + process.exitValue() + ")");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Trivy " + step + " 실행 실패: 실행 파일/작업 디렉터리 확인 필요");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Trivy " + step + " 중단");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
