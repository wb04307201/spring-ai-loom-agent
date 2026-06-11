package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Python 后端 —— Stub，Task 5 填充 */
public record PythonBuildStrategy() implements BuildStrategy {
    @Override public List<String> markerFiles() { return List.of("requirements.txt", "pyproject.toml"); }
    @Override public List<List<String>> buildCommands() { return List.of(); }
    @Override public List<String> artifactCandidates() { return List.of("."); }
    @Override public boolean isLongRunning() { return true; }
    @Override public File writeDockerfile(Path p, ResolvedImage i, int port, String a) throws IOException {
        throw new UnsupportedOperationException("TODO Task 5");
    }
}
