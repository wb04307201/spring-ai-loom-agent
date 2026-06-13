package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NpmBackendBuildStrategyTest {

    @Test
    void markerFiles_containsPackageJson() {
        assertThat(new NpmBackendBuildStrategy().markerFiles()).contains("package.json");
    }

    @Test
    void buildCommands_npmCiThenBuild() {
        BuildStrategy s = new NpmBackendBuildStrategy();
        List<List<String>> cmds = s.buildCommands();
        assertThat(cmds).hasSize(2);
        // 第一步：npm ci
        String first = String.join(" ", cmds.get(0));
        assertThat(first).contains("npm").contains("ci");
        // 第二步：npm run build
        String second = String.join(" ", cmds.get(1));
        assertThat(second).contains("npm").contains("run").contains("build");
    }

    @Test
    void artifactCandidates_distBuildNext() {
        assertThat(new NpmBackendBuildStrategy().artifactCandidates())
                .containsExactly("dist", "build", ".next");
    }

    @Test
    void isLongRunning_true() {
        assertThat(new NpmBackendBuildStrategy().isLongRunning()).isTrue();
    }

    @Test
    void writeDockerfile_copiesArtifactAndInstalls() throws Exception {
        Path tmp = Files.createTempDirectory("loom-npm-test-");
        try {
            Files.createDirectories(tmp.resolve("dist"));
            Files.writeString(tmp.resolve("dist/index.js"), "console.log('hi');");
            Files.writeString(tmp.resolve("package.json"), "{\"name\":\"x\"}");

            ResolvedImage image = new ResolvedImage("node20", "node:20-alpine", List.of("node", "dist/index.js"));
            BuildStrategy s = new NpmBackendBuildStrategy();
            File dockerfile = s.writeDockerfile(tmp, image, 3000, "dist");

            String content = Files.readString(dockerfile.toPath());
            assertThat(content).contains("FROM node:20-alpine");
            assertThat(content).contains("EXPOSE 3000");
            assertThat(content).contains("COPY package*.json ./");
            assertThat(content).contains("COPY dist ./dist");
            assertThat(content).contains("ENTRYPOINT");
            assertThat(content).contains("node").contains("dist/index.js");
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
