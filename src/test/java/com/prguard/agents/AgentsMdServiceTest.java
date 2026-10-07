package com.prguard.agents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentsMdServiceTest {

    @Test
    void javaVersion_readsGradleToolchainAndSourceCompatibility() {
        assertThat(AgentsMdService.javaVersion(AgentsMdService.GRADLE_JAVA, "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }"))
                .isEqualTo(21);
        assertThat(AgentsMdService.javaVersion(AgentsMdService.GRADLE_JAVA, "sourceCompatibility = JavaVersion.VERSION_17")).isEqualTo(17);
        assertThat(AgentsMdService.javaVersion(AgentsMdService.GRADLE_JAVA, "sourceCompatibility = '11'")).isEqualTo(11);
        assertThat(AgentsMdService.javaVersion(AgentsMdService.GRADLE_JAVA, "plugins { id 'java' }")).isNull();
    }

    @Test
    void javaVersion_readsMavenProperties() {
        assertThat(AgentsMdService.javaVersion(AgentsMdService.MAVEN_JAVA, "<properties><java.version>17</java.version></properties>")).isEqualTo(17);
        assertThat(AgentsMdService.javaVersion(AgentsMdService.MAVEN_JAVA, "<maven.compiler.release>21</maven.compiler.release>")).isEqualTo(21);
    }
}
