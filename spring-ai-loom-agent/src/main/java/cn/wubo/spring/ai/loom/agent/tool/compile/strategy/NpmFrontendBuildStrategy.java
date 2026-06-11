package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Node.js 前端（构建产物为静态文件 → nginx serve）—— <b>占位实现</b>。
 * <p>
 * 当前状态（Task 2）：
 * <ul>
 *   <li>{@link #markerFiles()}、{@link #artifactCandidates()}、{@link #isLongRunning()} 已可用</li>
 *   <li>{@link #buildCommands()} 暂空（由 Task 4 填充 npm ci / npm run build）</li>
 *   <li>{@link #writeDockerfile(Path, ResolvedImage, int, String)} 抛 {@link UnsupportedOperationException}</li>
 * </ul>
 * <p>
 * 在 Task 4 完成前，本策略可被 factory 识别，但实际部署会失败 —— 预期行为。
 */
public record NpmFrontendBuildStrategy() implements BuildStrategy {
    @Override public List<String> markerFiles() { return List.of("package.json"); }
    @Override public List<List<String>> buildCommands() { return List.of(); }
    @Override public List<String> artifactCandidates() { return List.of("dist", "build", ".next"); }
    @Override public boolean isLongRunning() { return false; }
    @Override public File writeDockerfile(Path p, ResolvedImage i, int port, String a) throws IOException {
        throw new UnsupportedOperationException("TODO Task 4: NpmFrontendBuildStrategy.writeDockerfile");
    }
}
