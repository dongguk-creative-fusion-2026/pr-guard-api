package com.prguard.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** git 명령을 프로세스로 실행한다. 대화형 프롬프트와 외부 프로토콜은 막는다. */
@Component
public class GitRunner {

    private static final int MAX_ERROR_CHARS = 2000;

    private final Duration timeout;

    public GitRunner(WorkspaceProperties props) {
        this.timeout = props.gitTimeout();
    }

    public String run(Path dir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        // 레포가 보낸 설정으로 로컬 파일이나 다른 프로토콜을 읽지 못하게 한다
        command.addAll(List.of("-c", "protocol.file.allow=never", "-c", "core.hooksPath=/dev/null",
                "-c", "core.quotepath=false"));
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_LFS_SKIP_SMUDGE", "1");
        pb.environment().put("LC_ALL", "C");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new GitException("git 실행 실패: " + e.getMessage());
        }
        CompletableFuture<String> out = read(process.getInputStream());
        CompletableFuture<String> err = read(process.getErrorStream());
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new GitException("git 시간 초과 (" + timeout.toSeconds() + "s): " + String.join(" ", args));
            }
            if (process.exitValue() != 0) {
                String message = err.join().strip();
                if (message.length() > MAX_ERROR_CHARS) {
                    message = message.substring(0, MAX_ERROR_CHARS);
                }
                throw new GitException("git " + args[0] + " 실패 (" + process.exitValue() + "): " + message);
            }
            return out.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("git 중단: " + String.join(" ", args));
        }
    }

    private static CompletableFuture<String> read(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
