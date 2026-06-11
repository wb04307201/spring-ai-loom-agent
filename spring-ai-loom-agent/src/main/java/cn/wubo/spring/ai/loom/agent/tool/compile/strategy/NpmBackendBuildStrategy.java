package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Node.js 后端（长驻进程，例如 Express / Nest / Koa）—— <b>占位实现</b>。
 * <p>
 * 当前状态（Task 2）：
 * <ul>
 *   <li>{@link #markerFiles()}、{@link #artifactCandidates()}、{@link #isLongRunning()} 已可用</li>
 *   <li>{@link #buildCommands()} 暂空（由 Task 3 填充 npm ci / npm run build 等）</li>
 *   <li>{@link #writeDockerfile(Path, ResolvedImage, int, String)} 抛 {@link UnsupportedOperationException}</li>
 * </ul>
 * <p>
 * 在 Task 3 完成前，本策略虽可被 {@link BuildStrategyFactory} 识别（{@code instanceof}），
 * 但实际部署会失败 —— 工具上层在 {@code buildTool=npm} 走完 factory 后会卡在 buildCommands/dockefile 抛错。
 * 这是预期行为（Task 2 不动核心逻辑，只建立可识别性）。
 */
public record NpmBackendBuildStrategy() implements BuildStrategy {
    @Override public List<String> markerFiles() { return List.of("package.json"); }
    @Override public List<List<String>> buildCommands() { return List.of(); }
    @Override public List<String> artifactCandidates() { return List.of("dist", "build", ".next"); }
    @Override public boolean isLongRunning() { return true; }
    @Override public File writeDockerfile(Path p, ResolvedImage i, int port, String a) throws IOException {
        throw new UnsupportedOperationException("TODO Task 3: NpmBackendBuildStrategy.writeDockerfile");
    }
}
