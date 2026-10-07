package com.prguard.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.prguard.execution.TestRunner.Phase;
import com.prguard.execution.TestRunner.TestRunJob;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class KubernetesTestRunnerTest {

    private static final ExecutionProperties PROPS = new ExecutionProperties(ExecutionProperties.Runner.KUBERNETES,
            "ghcr.io/x/runner:1", "https://api.example", "prguard-runs", "gvisor", "2", "3Gi",
            Duration.ofMinutes(15), Duration.ofSeconds(2));

    @Test
    void jobFor_runsUntrustedCodeWithLeastPrivilege() {
        Job job = KubernetesTestRunner.jobFor(
                new TestRunJob(42, "HEAD", "https://github.com/o/r.git", 7, "abc1234def", "https://api/x", "tok"), PROPS);

        assertThat(job.getMetadata().getName()).isEqualTo("prguard-run-42-head");
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(900L);
        assertThat(job.getSpec().getTtlSecondsAfterFinished()).isPositive();

        PodSpec pod = job.getSpec().getTemplate().getSpec();
        assertThat(pod.getRestartPolicy()).isEqualTo("Never");
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getRuntimeClassName()).isEqualTo("gvisor");
        assertThat(pod.getSecurityContext().getRunAsNonRoot()).isTrue();
        assertThat(pod.getSecurityContext().getSeccompProfile().getType()).isEqualTo("RuntimeDefault");
        assertThat(job.getSpec().getTemplate().getMetadata().getLabels()).containsEntry("app", "prguard-test-run");

        Container c = pod.getContainers().get(0);
        assertThat(c.getImage()).isEqualTo("ghcr.io/x/runner:1");
        assertThat(c.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
        assertThat(c.getSecurityContext().getCapabilities().getDrop()).containsExactly("ALL");
        assertThat(c.getResources().getLimits()).containsKeys("cpu", "memory");
        assertThat(c.getEnv()).extracting(EnvVar::getName)
                .contains("REPO_URL", "SHA", "PR_NUMBER", "CALLBACK_URL", "RUN_TOKEN");
    }

    @Test
    void jobFor_withoutRuntimeClass_usesClusterDefault() {
        ExecutionProperties plain = new ExecutionProperties(PROPS.runner(), PROPS.image(), PROPS.callbackUrl(),
                PROPS.namespace(), "", PROPS.cpu(), PROPS.memory(), PROPS.timeout(), PROPS.pollInterval());

        Job job = KubernetesTestRunner.jobFor(new TestRunJob(1, "BASE", "u", 1, "abcdefg1", "c", "t"), plain);

        assertThat(job.getSpec().getTemplate().getSpec().getRuntimeClassName()).isNull();
    }

    @Test
    void podStatus_mapsKubernetesStatesToStages() {
        assertThat(KubernetesTestRunner.podStatus(pod("Pending", null, false)).phase()).isEqualTo(Phase.SCHEDULING);
        assertThat(KubernetesTestRunner.podStatus(pod("Pending", null, true)).phase()).isEqualTo(Phase.PULLING);
        assertThat(KubernetesTestRunner.podStatus(pod("Pending", "ContainerCreating", true)).phase()).isEqualTo(Phase.PULLING);
        assertThat(KubernetesTestRunner.podStatus(pod("Pending", "ImagePullBackOff", true)).phase()).isEqualTo(Phase.FAILED);
        assertThat(KubernetesTestRunner.podStatus(pod("Running", null, true)).phase()).isEqualTo(Phase.RUNNING);
        assertThat(KubernetesTestRunner.podStatus(pod("Succeeded", null, true)).phase()).isEqualTo(Phase.SUCCEEDED);
    }

    private static Pod pod(String phase, String waitingReason, boolean scheduled) {
        PodBuilder b = new PodBuilder().withNewStatus().withPhase(phase)
                .addNewCondition().withType("PodScheduled").withStatus(scheduled ? "True" : "False").endCondition()
                .endStatus();
        if (waitingReason != null) {
            b.editStatus().addNewContainerStatus().withName("runner")
                    .withNewState().withNewWaiting().withReason(waitingReason).endWaiting().endState()
                    .endContainerStatus().endStatus();
        }
        return b.build();
    }
}
