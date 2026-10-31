package com.prguard.trivy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyProcessTest {
    @TempDir Path temp;
    private final TrivyProcess process = new TrivyProcess();

    @Test
    void runsWithoutShellAndUsesControlledWorkingDirectoryAndEnvironment() throws Exception {
        process.run(command("environment"), temp, Duration.ofSeconds(10), "test");
        String env = Files.readString(temp.resolve("environment.txt"));
        assertThat(env).contains("HOME=" + temp, "cwd=" + temp);
        assertThat(env).doesNotContain("GITHUB_TOKEN=", "OPENAI_API_KEY=", "API_TOKEN=", "TRIVY_");
    }

    @Test
    void handlesNonzeroExitAndMissingBinary() throws Exception {
        assertThatThrownBy(() -> process.run(command("fail"), temp, Duration.ofSeconds(10), "test"))
                .hasMessageContaining("exit=7");
        assertThatThrownBy(() -> process.run(List.of(temp.resolve("does-not-exist").toString()),
                temp, Duration.ofSeconds(1), "test")).hasMessageContaining("실행 실패");
    }

    @Test
    void killsTimedOutChild() throws Exception {
        assertThatThrownBy(() -> process.run(command("sleep"), temp, Duration.ofSeconds(2), "test"))
                .hasMessageContaining("시간 초과");
        long pid = Long.parseLong(Files.readString(temp.resolve("pid")));
        var handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(handle.get().isAlive()).isFalse();
        }
    }

    private List<String> command(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return List.of(java, "-cp", classes, Child.class.getName(), mode);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "environment" -> Files.writeString(Path.of("environment.txt"),
                        "cwd=" + Path.of("").toAbsolutePath() + "\n" + System.getenv());
                case "fail" -> System.exit(7);
                case "sleep" -> {
                    Files.writeString(Path.of("pid"), String.valueOf(ProcessHandle.current().pid()));
                    Thread.sleep(30_000);
                }
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
