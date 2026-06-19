package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.loom.compile.core.CompileAndDeployOperations;
import cn.wubo.loom.compile.core.CompileAndDeployResult;
import cn.wubo.loom.compile.core.CompileConfig;
import cn.wubo.loom.compile.core.ImageTemplate;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ICompileAndDeployTool} 的默认实现：委托给 {@link CompileAndDeployOperations}。
 * <p>
 * 本类是薄包装层，负责：
 * <ul>
 *   <li>从 {@link ToolContext} 提取 username，计算 workspaceBasePath</li>
 *   <li>将 {@link LoomAgentProperties.CompileProperty} 转换为 {@link CompileConfig}</li>
 * </ul>
 * 所有管线逻辑（clone/build/docker/health/process management）均位于 {@code loom-compile-core}。
 */
public class DefaultCompileAndDeployTool implements ICompileAndDeployTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultCompileAndDeployTool.class);
    private static final String DEFAULT_FILE_BASE_PATH = ".local/file";

    private final CompileAndDeployOperations operations;
    private final String fileBasePath;

    public DefaultCompileAndDeployTool(LoomAgentProperties properties) {
        this(properties.getCompile(), properties.getMaven() != null ? properties.getMaven().getMavenHome() : null,
                properties.getFileBasePath());
    }

    /** 供测试直接注入 */
    DefaultCompileAndDeployTool(LoomAgentProperties.CompileProperty compile, String mavenHome, String fileBasePath) {
        this.fileBasePath = (fileBasePath != null && !fileBasePath.isBlank()) ? fileBasePath : DEFAULT_FILE_BASE_PATH;
        String configured = compile != null ? compile.getMavenHome() : mavenHome;

        CompileConfig config = toCompileConfig(compile);
        this.operations = new CompileAndDeployOperations(configured, config);
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, fileBasePath={}",
                compile != null && compile.isEnabled(), configured, this.fileBasePath);
    }

    // ==================== Tool Entry ====================

    @Override
    public CompileAndDeployResult compileAndDeploy(Map<String, Object> params, ToolContext toolContext) {
        String username = username(toolContext);
        if (username == null) {
            return CompileAndDeployResult.fail(null, null, null, null, null, null,
                    List.of(), "无法获取用户名，请通过登录态调用");
        }

        Path workspaceBasePath = getUserFileDir(username);
        return operations.compileAndDeploy(workspaceBasePath, params);
    }

    // ==================== Username / Path ====================

    Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private static String username(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object u = toolContext.getContext().get("username");
        return u == null ? null : u.toString();
    }

    // ==================== Config Conversion ====================

    private static CompileConfig toCompileConfig(LoomAgentProperties.CompileProperty compile) {
        if (compile == null) {
            return new CompileConfig();
        }
        Map<String, ImageTemplate> templates = Map.of();
        if (compile.getImageTemplates() != null && !compile.getImageTemplates().isEmpty()) {
            templates = compile.getImageTemplates().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> new ImageTemplate(e.getValue().getImage(), e.getValue().getCommand())
                    ));
        }
        return new CompileConfig(
                compile.getMavenTimeoutMs(),
                compile.getDockerBuildTimeoutMs(),
                compile.getDockerRunTimeoutMs(),
                compile.getHealthCheckMaxWaitMs(),
                compile.getHealthCheckIntervalMs(),
                compile.isKeepWorkspace(),
                compile.getExtraRunArgs() != null ? compile.getExtraRunArgs() : List.of(),
                templates
        );
    }
}
