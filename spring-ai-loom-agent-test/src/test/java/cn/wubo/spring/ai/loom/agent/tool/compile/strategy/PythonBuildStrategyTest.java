package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PythonBuildStrategyTest {

    @Test
    void markerFiles_containsRequirementsAndPyproject() {
        assertThat(new PythonBuildStrategy().markerFiles())
                .contains("requirements.txt", "pyproject.toml");
    }

    @Test
    void buildCommands_empty() {
        // pip install 在 Dockerfile 内做，build 阶段不执行独立命令
        assertThat(new PythonBuildStrategy().buildCommands()).isEmpty();
    }

    @Test
    void artifactCandidates_currentDir() {
        // Python 镜像直接 COPY 整个项目（白名单 .py + requirements.txt）
        assertThat(new PythonBuildStrategy().artifactCandidates()).containsExactly(".");
    }

    @Test
    void isLongRunning_true() {
        assertThat(new PythonBuildStrategy().isLongRunning()).isTrue();
    }

    @Test
    void writeDockerfile_pipInstallAndCopy() throws Exception {
        Path tmp = Files.createTempDirectory("loom-py-test-");
        try {
            Files.writeString(tmp.resolve("requirements.txt"), "fastapi==0.100.0\nuvicorn[standard]\n");
            Files.writeString(tmp.resolve("app.py"), "from fastapi import FastAPI\napp = FastAPI()\n");

            ResolvedImage image = new ResolvedImage("python3", "python:3.12-slim",
                    List.of("uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"));
            BuildStrategy s = new PythonBuildStrategy();
            File dockerfile = s.writeDockerfile(tmp, image, 8000, ".");

            String content = Files.readString(dockerfile.toPath());
            assertThat(content).contains("FROM python:3.12-slim");
            assertThat(content).contains("WORKDIR /app");
            assertThat(content).contains("COPY requirements.txt .");
            assertThat(content).contains("pip install --no-cache-dir -r requirements.txt");
            assertThat(content).contains("COPY . .");
            assertThat(content).contains("EXPOSE 8000");
            // 锁定 toJsonArray 输出格式：Jackson 默认 compact 形式，无空格分隔
            assertThat(content).contains("ENTRYPOINT [\"uvicorn\",\"app:app\",\"--host\",\"0.0.0.0\",\"--port\",\"8000\"]");
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
