package cn.wubo.spring.ai.loom.agent.tool.compile.strategy;

import cn.wubo.spring.ai.loom.agent.tool.compile.DefaultCompileAndDeployTool.ResolvedImage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 构建策略：定义一种栈（Java / Node / Python）的"找项目 → 编译 → 找产物 → 写 Dockerfile"全流程。
 * <p>
 * 每个实现是一个 record（无状态），由 {@link BuildStrategyFactory} 选中后由
 * {@link DefaultCompileAndDeployTool} 调用。
 */
public sealed interface BuildStrategy
        permits MavenBuildStrategy, NpmBackendBuildStrategy, NpmFrontendBuildStrategy, PythonBuildStrategy {

    /**
     * 标识此策略的 marker 文件名（任一存在于 projectDir 即视为匹配）。
     */
    List<String> markerFiles();

    /**
     * 编译命令列表（按顺序执行）。空列表表示无独立 build 步骤（如 Python 走 Dockerfile 内 pip install）。
     */
    List<List<String>> buildCommands();

    /**
     * 产物相对路径候选（相对 projectDir）。按列表顺序探查，第一个存在的目录 / 文件即胜出。
     */
    List<String> artifactCandidates();

    /**
     * 此策略是否产生长驻进程（影响 Dockerfile 形态 —— 长驻用 ENTRYPOINT exec，静态文件用 nginx）。
     */
    boolean isLongRunning();

    /**
     * 在 {@code projectDir} 下生成 Dockerfile。
     *
     * @param projectDir   工作目录（应包含 build 产物）
     * @param image        已解析的基础镜像（FROM 字段）
     * @param containerPort 容器内应用监听端口（EXPOSE 字段）
     * @param artifact     {@link #artifactCandidates} 探查到的实际产物路径（相对 projectDir）
     * @return 写入的 Dockerfile 文件
     */
    File writeDockerfile(Path projectDir, ResolvedImage image, int containerPort, String artifact) throws IOException;

    /**
     * 在 {@code candidateDir}（由 {@link #artifactCandidates()} 解析出的目录）下挑出具体产物文件 / 目录。
     * <p>
     * 缺省实现返回 {@code candidateDir} 自身（Node / Python 整体拷贝目录即可）。
     * Maven 在此覆盖为：{@code target/} 下选体积最大的非 {@code .original.jar} / {@code -sources.jar} / {@code -javadoc.jar} jar。
     *
     * @return 选中的产物绝对路径；找不到返回 null（让上层 fail-fast）
     */
    default Path findArtifact(Path candidateDir) {
        return candidateDir;
    }
}
