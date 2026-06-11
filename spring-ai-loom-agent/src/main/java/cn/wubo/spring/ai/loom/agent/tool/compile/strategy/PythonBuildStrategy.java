package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Python 后端（Flask / FastAPI / Django 等长驻进程）—— <b>占位实现</b>。
 * <p>
 * 当前状态（Task 2）：
 * <ul>
 *   <li>{@link #markerFiles()}、{@link #artifactCandidates()}、{@link #isLongRunning()} 已可用</li>
 *   <li>{@link #buildCommands()} 故意为空 —— Python 走 Dockerfile 内 pip install，无独立 build 步骤</li>
 *   <li>{@link #writeDockerfile(Path, ResolvedImage, int, String)} 抛 {@link UnsupportedOperationException}</li>
 * </ul>
 * <p>
 * 在 Task 5 完成前，本策略可被 factory 识别，但实际部署会失败 —— 预期行为。
 */
public record PythonBuildStrategy() implements BuildStrategy {
    @Override public List<String> markerFiles() { return List.of("requirements.txt", "pyproject.toml"); }
    @Override public List<List<String>> buildCommands() { return List.of(); }
    @Override public List<String> artifactCandidates() { return List.of("."); }
    @Override public boolean isLongRunning() { return true; }
    @Override public File writeDockerfile(Path p, ResolvedImage i, int port, String a) throws IOException {
        throw new UnsupportedOperationException("TODO Task 5: PythonBuildStrategy.writeDockerfile");
    }
}
