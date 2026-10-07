package com.prguard.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 로컬 개발용: 이 서버가 있는 곳의 docker 로 러너 컨테이너를 띄운다. 쿠버네티스 없이 같은 흐름을 확인할 때 쓴다.
 * 격리는 쿠버네티스 쪽과 같은 수준으로 맞춘다 (root 아님, capability 없음, CPU · 메모리 제한).
 */
public class DockerTestRunner implements TestRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerTestRunner.class);

    private final ExecutionProperties props;

    public DockerTestRunner(ExecutionProperties props) {
        this.props = props;
    }

    @Override
    public String start(TestRunJob job) {
        String name = "prguard-run-" + job.runId() + "-" + job.side().toLowerCase();
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--name", name,
                "--user", "1000:1000", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "--cpus", cpus(props.cpu()), "--memory", dockerMemory(props.memory()),
                "--add-host", "host.docker.internal:host-gateway",
                "--label", "app=" + KubernetesTestRunner.APP_LABEL));
        for (String[] e : new String[][] {
                {"REPO_URL", job.repoUrl()}, {"SHA", job.sha()}, {"PR_NUMBER", String.valueOf(job.prNumber())},
                {"SIDE", job.side()}, {"CALLBACK_URL", job.callbackUrl()}, {"RUN_TOKEN", job.token()}}) {
            cmd.add("-e");
            cmd.add(e[0] + "=" + e[1]);
        }
        cmd.add(props.image());
        run(cmd);
        log.info("실행 검증 컨테이너 시작 {} ({} {})", name, job.side(), job.sha().substring(0, 7));
        return name;
    }

    @Override
    public RunnerStatus status(String name) {
        String state;
        try {
            state = run(List.of("docker", "inspect", "-f", "{{.State.Status}} {{.State.ExitCode}}", name)).strip();
        } catch (ExecutionException e) {
            return null;
        }
        if (state.startsWith("running")) {
            return new RunnerStatus(Phase.RUNNING, "컨테이너 실행 중");
        }
        if (state.startsWith("exited")) {
            return state.endsWith(" 0") ? new RunnerStatus(Phase.SUCCEEDED, "완료") : new RunnerStatus(Phase.FAILED, "종료 코드 " + state.substring(7));
        }
        return new RunnerStatus(Phase.PULLING, state);
    }

    @Override
    public void cleanup(String name) {
        try {
            run(List.of("docker", "rm", "-f", name));
        } catch (ExecutionException e) {
            log.warn("컨테이너 정리 실패 {}: {}", name, e.getMessage());
        }
    }

    private static String run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new ExecutionException("docker 시간 초과: " + String.join(" ", cmd.subList(0, 2)));
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.exitValue() != 0) {
                throw new ExecutionException("docker 실패 (" + p.exitValue() + "): " + out.strip());
            }
            return out;
        } catch (IOException e) {
            throw new ExecutionException("docker 실행 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionException("docker 중단");
        }
    }

    /** "500m" → "0.5", "2" → "2" */
    static String cpus(String k8s) {
        return k8s.endsWith("m") ? String.valueOf(Integer.parseInt(k8s.substring(0, k8s.length() - 1)) / 1000.0) : k8s;
    }

    /** "2Gi" → "2g", "512Mi" → "512m" */
    static String dockerMemory(String k8s) {
        return k8s.replace("Gi", "g").replace("Mi", "m");
    }
}
