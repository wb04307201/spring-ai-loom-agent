package cn.wubo.loom.compile.core;

import java.util.List;
import java.util.Map;

/**
 * 编译部署配置。
 *
 * @param mavenTimeoutMs        Maven 构建超时（毫秒），也用于 npm 等构建步骤
 * @param dockerBuildTimeoutMs  Docker 镜像构建超时（毫秒）
 * @param dockerRunTimeoutMs    Docker 容器启动超时（毫秒）
 * @param healthCheckMaxWaitMs  健康检查最大等待时间（毫秒）
 * @param healthCheckIntervalMs 健康检查轮询间隔（毫秒）
 * @param keepWorkspace         是否保留工作区目录（默认 false，失败时保留供排障）
 * @param extraRunArgs          额外 docker run 参数（如 ["--network", "host"]）
 * @param imageTemplates        基础镜像模板：别名 -> 镜像配置（如 "java17" -> eclipse-temurin:17-jre-alpine）
 */
public record CompileConfig(
        long mavenTimeoutMs,
        long dockerBuildTimeoutMs,
        long dockerRunTimeoutMs,
        long healthCheckMaxWaitMs,
        long healthCheckIntervalMs,
        boolean keepWorkspace,
        List<String> extraRunArgs,
        Map<String, ImageTemplate> imageTemplates
) {
    /**
     * 便捷构造器：使用合理默认值。
     */
    public CompileConfig {
        if (mavenTimeoutMs <= 0) mavenTimeoutMs = 300_000L;
        if (dockerBuildTimeoutMs <= 0) dockerBuildTimeoutMs = 300_000L;
        if (dockerRunTimeoutMs <= 0) dockerRunTimeoutMs = 60_000L;
        if (healthCheckMaxWaitMs <= 0) healthCheckMaxWaitMs = 30_000L;
        if (healthCheckIntervalMs <= 0) healthCheckIntervalMs = 500L;
        if (extraRunArgs == null) extraRunArgs = List.of();
        if (imageTemplates == null) imageTemplates = Map.of();
    }

    /**
     * 使用默认超时值的便捷构造。
     */
    public CompileConfig() {
        this(300_000L, 300_000L, 60_000L, 30_000L, 500L, false, List.of(), getDefaultImageTemplates());
    }

    private static Map<String, ImageTemplate> getDefaultImageTemplates() {
        return Map.of(
                "java17", new ImageTemplate("eclipse-temurin:17-jre-alpine", List.of("java", "-jar", "app.jar")),
                "java21", new ImageTemplate("eclipse-temurin:21-jre-alpine", List.of("java", "-jar", "app.jar")),
                "nginx", new ImageTemplate("nginx:alpine", List.of("/docker-entrypoint.sh", "nginx", "-g", "daemon off;")),
                "python3", new ImageTemplate("python:3.12-slim", List.of("uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000")),
                "node20", new ImageTemplate("node:20-alpine", List.of("node", "server.js")),
                "node20-serve", new ImageTemplate("node:20-alpine", List.of("npx", "serve", "-s", "-l", "80"))
        );
    }
}
