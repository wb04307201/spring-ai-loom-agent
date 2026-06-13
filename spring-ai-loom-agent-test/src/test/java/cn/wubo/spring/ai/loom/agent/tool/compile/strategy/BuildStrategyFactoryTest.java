package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuildStrategyFactoryTest {

    @Test
    void forBuildTool_maven_returnsMavenStrategy() {
        assertThat(BuildStrategyFactory.forBuildTool("maven"))
                .isInstanceOf(MavenBuildStrategy.class);
    }

    @Test
    void forBuildTool_npmBackend_returnsNpmBackendStrategy() {
        assertThat(BuildStrategyFactory.forBuildTool("npm"))
                .isInstanceOf(NpmBackendBuildStrategy.class);
    }

    @Test
    void forBuildTool_npmFrontend_returnsNpmFrontendStrategy() {
        assertThat(BuildStrategyFactory.forBuildTool("npm-frontend"))
                .isInstanceOf(NpmFrontendBuildStrategy.class);
    }

    @Test
    void forBuildTool_python_returnsPythonStrategy() {
        assertThat(BuildStrategyFactory.forBuildTool("pip"))
                .isInstanceOf(PythonBuildStrategy.class);
    }

    @Test
    void forBuildTool_unknown_throws() {
        assertThatThrownBy(() -> BuildStrategyFactory.forBuildTool("rust"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rust");
    }

    @Test
    void forBuildTool_nullOrBlank_returnsNull() {
        // 显式 null/blank 走 autoDetect，不在 factory 这层处理
        assertThat(BuildStrategyFactory.forBuildTool(null)).isNull();
        assertThat(BuildStrategyFactory.forBuildTool("")).isNull();
    }

    @Test
    void autoDetect_pomXml_returnsMaven(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("pom.xml"));
        assertThat(BuildStrategyFactory.autoDetect(tmp)).isInstanceOf(MavenBuildStrategy.class);
    }

    @Test
    void autoDetect_packageJson_returnsNpmBackend(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("package.json"));
        assertThat(BuildStrategyFactory.autoDetect(tmp)).isInstanceOf(NpmBackendBuildStrategy.class);
    }

    @Test
    void autoDetect_requirementsTxt_returnsPython(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("requirements.txt"));
        assertThat(BuildStrategyFactory.autoDetect(tmp)).isInstanceOf(PythonBuildStrategy.class);
    }

    @Test
    void autoDetect_pyprojectToml_returnsPython(@TempDir Path tmp) throws Exception {
        Files.createFile(tmp.resolve("pyproject.toml"));
        assertThat(BuildStrategyFactory.autoDetect(tmp)).isInstanceOf(PythonBuildStrategy.class);
    }

    @Test
    void autoDetect_nothing_throws(@TempDir Path tmp) {
        assertThatThrownBy(() -> BuildStrategyFactory.autoDetect(tmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法识别");
    }

    @Test
    void autoDetect_multipleMarkers_prefersMaven(@TempDir Path tmp) throws Exception {
        // 优先级：pom.xml > package.json > requirements.txt
        Files.createFile(tmp.resolve("pom.xml"));
        Files.createFile(tmp.resolve("package.json"));
        assertThat(BuildStrategyFactory.autoDetect(tmp)).isInstanceOf(MavenBuildStrategy.class);
    }
}
