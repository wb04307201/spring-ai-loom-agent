package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NpmFrontendBuildStrategyTest {

    @Test
    void markerFiles_containsPackageJson() {
        assertThat(new NpmFrontendBuildStrategy().markerFiles()).contains("package.json");
    }

    @Test
    void buildCommands_npmCiThenBuild() {
        List<List<String>> cmds = new NpmFrontendBuildStrategy().buildCommands();
        assertThat(cmds).hasSize(2);
        assertThat(String.join(" ", cmds.get(0))).contains("npm ci");
        assertThat(String.join(" ", cmds.get(1))).contains("npm run build");
    }

    @Test
    void artifactCandidates_distBuildNext() {
        assertThat(new NpmFrontendBuildStrategy().artifactCandidates())
                .containsExactly("dist", "build", ".next");
    }

    @Test
    void isLongRunning_false() {
        assertThat(new NpmFrontendBuildStrategy().isLongRunning()).isFalse();
    }

    @Test
    void writeDockerfile_copiesArtifactToNginxHtml() throws Exception {
        Path tmp = Files.createTempDirectory("loom-npmfe-test-");
        try {
            Files.createDirectories(tmp.resolve("dist"));
            Files.writeString(tmp.resolve("dist/index.html"), "<h1>hi</h1>");

            // 前端用 nginx 镜像
            ResolvedImage image = new ResolvedImage("nginx", "nginx:1.27-alpine",
                    List.of("nginx", "-g", "daemon off;"));
            BuildStrategy s = new NpmFrontendBuildStrategy();
            File dockerfile = s.writeDockerfile(tmp, image, 80, "dist");

            String content = Files.readString(dockerfile.toPath());
            assertThat(content).contains("FROM nginx:1.27-alpine");
            assertThat(content).contains("EXPOSE 80");
            assertThat(content).contains("COPY dist /usr/share/nginx/html");
            assertThat(content).contains("ENTRYPOINT");
            assertThat(content).contains("nginx").contains("daemon off");
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
