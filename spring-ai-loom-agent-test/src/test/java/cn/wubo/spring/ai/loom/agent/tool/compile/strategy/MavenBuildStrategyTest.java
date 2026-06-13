package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MavenBuildStrategyTest {

    @Test
    void markerFiles_containsPomXml() {
        BuildStrategy s = new MavenBuildStrategy();
        assertThat(s.markerFiles()).contains("pom.xml");
    }

    @Test
    void buildCommands_runsMavenCleanPackage() {
        BuildStrategy s = new MavenBuildStrategy();
        assertThat(s.buildCommands()).isNotEmpty();
        // 第一个 build command 应该是 mvn clean package -DskipTests
        String firstCmd = String.join(" ", s.buildCommands().get(0));
        assertThat(firstCmd).contains("mvn").contains("clean").contains("package");
    }

    @Test
    void artifactCandidates_targetsJar() {
        BuildStrategy s = new MavenBuildStrategy();
        assertThat(s.artifactCandidates()).contains("target");
    }

    @Test
    void isLongRunning_true() {
        assertThat(new MavenBuildStrategy().isLongRunning()).isTrue();
    }
}
