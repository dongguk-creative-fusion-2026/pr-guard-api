package com.prguard.execution;

import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 러너를 쿠버네티스 Job 으로 띄운다. 클러스터 접속은 fabric8 기본 규칙을 따른다
 * (클러스터 안이면 서비스 계정, 밖이면 KUBECONFIG). 어느 클라우드든 같은 코드로 동작한다.
 *
 * Job 은 남의 코드를 돌리므로 최소 권한으로 만든다: root 아님, 권한 상승 · 리눅스 capability 없음,
 * 서비스 계정 토큰 없음, CPU · 메모리 · 시간 제한, 끝나면 자동 삭제, 실패해도 재시도 안 함.
 */
public class KubernetesTestRunner implements TestRunner {

    private static final Logger log = LoggerFactory.getLogger(KubernetesTestRunner.class);
    static final String APP_LABEL = "prguard-test-run";
    private static final long USER_ID = 1000L;
    /** Job 이 끝나고 이만큼 지나면 쿠버네티스가 지운다 (cleanup 을 못 불러도 남지 않게) */
    private static final int TTL_AFTER_FINISHED_SECONDS = 600;

    private final ExecutionProperties props;
    private final KubernetesClient client;

    public KubernetesTestRunner(ExecutionProperties props) {
        this(props, new KubernetesClientBuilder().build());
    }

    KubernetesTestRunner(ExecutionProperties props, KubernetesClient client) {
        this.props = props;
        this.client = client;
    }

    @Override
    public String start(TestRunJob job) {
        Job manifest = jobFor(job, props);
        client.batch().v1().jobs().inNamespace(props.namespace()).resource(manifest).create();
        log.info("실행 검증 Job 생성 {}/{} ({} {})", props.namespace(), manifest.getMetadata().getName(), job.side(),
                job.sha().substring(0, 7));
        return manifest.getMetadata().getName();
    }

    @Override
    public RunnerStatus status(String name) {
        List<Pod> pods = client.pods().inNamespace(props.namespace()).withLabel("job-name", name).list().getItems();
        if (pods.isEmpty()) {
            return new RunnerStatus(Phase.SCHEDULING, "Pod 만드는 중");
        }
        return podStatus(pods.get(0));
    }

    @Override
    public void cleanup(String name) {
        try {
            client.batch().v1().jobs().inNamespace(props.namespace()).withName(name)
                    .withPropagationPolicy(io.fabric8.kubernetes.api.model.DeletionPropagation.BACKGROUND).delete();
        } catch (RuntimeException e) {
            log.warn("Job 정리 실패 {}: {}", name, e.getMessage());
        }
    }

    /** Pod 상태를 화면용 단계로 바꾼다. */
    static RunnerStatus podStatus(Pod pod) {
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        if ("Succeeded".equals(phase)) {
            return new RunnerStatus(Phase.SUCCEEDED, "완료");
        }
        if ("Failed".equals(phase)) {
            return new RunnerStatus(Phase.FAILED, pod.getStatus().getReason() == null ? "실패" : pod.getStatus().getReason());
        }
        if ("Running".equals(phase)) {
            return new RunnerStatus(Phase.RUNNING, "컨테이너 실행 중");
        }
        List<ContainerStatus> containers = pod.getStatus() == null ? List.of() : pod.getStatus().getContainerStatuses();
        for (ContainerStatus c : containers == null ? List.<ContainerStatus>of() : containers) {
            ContainerStateWaiting waiting = c.getState() == null ? null : c.getState().getWaiting();
            if (waiting == null) {
                continue;
            }
            String reason = waiting.getReason();
            if ("ErrImagePull".equals(reason) || "ImagePullBackOff".equals(reason) || "InvalidImageName".equals(reason)) {
                return new RunnerStatus(Phase.FAILED, "러너 이미지를 받지 못함 (" + reason + ")");
            }
            if ("ContainerCreating".equals(reason)) {
                return new RunnerStatus(Phase.PULLING, "이미지 받는 중 · 컨테이너 만드는 중");
            }
            return new RunnerStatus(Phase.PULLING, reason);
        }
        boolean scheduled = pod.getStatus() != null && pod.getStatus().getConditions() != null
                && pod.getStatus().getConditions().stream()
                        .anyMatch(c -> "PodScheduled".equals(c.getType()) && "True".equals(c.getStatus()));
        return scheduled ? new RunnerStatus(Phase.PULLING, "이미지 받는 중")
                : new RunnerStatus(Phase.SCHEDULING, "노드 배정 기다리는 중");
    }

    /** 러너 Job 매니페스트. 클러스터 없이 테스트할 수 있게 따로 뺀다. */
    static Job jobFor(TestRunJob job, ExecutionProperties props) {
        String name = "prguard-run-" + job.runId() + "-" + job.side().toLowerCase();
        Map<String, Quantity> limits = Map.of("cpu", new Quantity(props.cpu()), "memory", new Quantity(props.memory()));
        Map<String, Quantity> requests = Map.of("cpu", new Quantity("250m"), "memory", new Quantity("512Mi"));
        List<EnvVar> env = List.of(
                new EnvVar("REPO_URL", job.repoUrl(), null),
                new EnvVar("SHA", job.sha(), null),
                new EnvVar("PR_NUMBER", String.valueOf(job.prNumber()), null),
                new EnvVar("SIDE", job.side(), null),
                new EnvVar("CALLBACK_URL", job.callbackUrl(), null),
                new EnvVar("RUN_TOKEN", job.token(), null));
        return new JobBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(Map.of("app", APP_LABEL, "prguard/run", String.valueOf(job.runId()),
                            "prguard/side", job.side().toLowerCase()))
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(props.timeout().toSeconds())
                    .withTtlSecondsAfterFinished(TTL_AFTER_FINISHED_SECONDS)
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(Map.of("app", APP_LABEL, "prguard/run", String.valueOf(job.runId())))
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withAutomountServiceAccountToken(false)
                            .withEnableServiceLinks(false)
                            .withRuntimeClassName(props.runtimeClass() == null || props.runtimeClass().isBlank()
                                    ? null : props.runtimeClass())
                            .withNewSecurityContext()
                                .withRunAsNonRoot(true)
                                .withRunAsUser(USER_ID)
                                .withRunAsGroup(USER_ID)
                                .withFsGroup(USER_ID)
                                .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                            .endSecurityContext()
                            .addNewContainer()
                                .withName("runner")
                                .withImage(props.image())
                                .withImagePullPolicy("IfNotPresent")
                                .withEnv(env)
                                .withNewResources().withLimits(limits).withRequests(requests).endResources()
                                .withNewSecurityContext()
                                    .withAllowPrivilegeEscalation(false)
                                    .withNewCapabilities().withDrop("ALL").endCapabilities()
                                .endSecurityContext()
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }
}
