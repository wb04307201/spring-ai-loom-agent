package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.loom.compile.core.CompileAndDeployOperations;
import cn.wubo.loom.compile.core.CompileAndDeployResult;
import cn.wubo.loom.compile.core.CompileConfig;
import cn.wubo.loom.compile.core.ImageTemplate;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.maven.MavenHomeResolver;
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
 * <li>从 {@link ToolContext} 提取 username，计算 workspaceBasePath</li>
 * <li>将 {@link LoomAgentProperties.CompileProperty} 转换为 {@link CompileConfig}</li>
 * </ul>
 * 所有管线逻辑（clone/build/docker/health/process management）均位于 {@code loom-compile-core}。
 */
public class DefaultCompileAndDeployTool implements ICompileAndDeployTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultCompileAndDeployTool.class);

    private final CompileAndDeployOperations operations;
    private final String fileBasePath;
    /** 显式配置的 workspace 根目录（compile.workspace-base-path），null → 派生自 loomHome。 */
    private final String workspaceBasePath;
    /** loomHome（默认 {@code ~/.loom}），workspace 派生根，null → 老默认 {@code user.home/.loom}。 */
    private final String loomHome;

    public DefaultCompileAndDeployTool(LoomAgentProperties properties) {
        this(properties.getCompile(), properties.getMaven() != null ? properties.getMaven().getMavenHome() : null,
                properties.getFileBasePath(),
                properties.getCompile() != null ? properties.getCompile().getWorkspaceBasePath() : null,
                properties.getLoomHome());
    }

    /**
     * 供测试直接注入
     */
    DefaultCompileAndDeployTool(LoomAgentProperties.CompileProperty compile, String mavenHome, String fileBasePath) {
        this(compile, mavenHome, fileBasePath, null, null);
    }

    /**
     * 供测试直接注入（全参）。workspace 解析优先级：
     * {@code workspaceBasePath} 显式配置 &gt; {@code loomHome} 派生 &gt; 老默认 {@code user.home/.loom}。
     */
    DefaultCompileAndDeployTool(LoomAgentProperties.CompileProperty compile, String mavenHome, String fileBasePath,
                                String workspaceBasePath, String loomHome) {
        this.fileBasePath = cn.wubo.loom.file.core.LoomPaths.orDefaultFileBase(fileBasePath);
        this.workspaceBasePath = (workspaceBasePath != null && !workspaceBasePath.isBlank()) ? workspaceBasePath : null;
        this.loomHome = (loomHome != null && !loomHome.isBlank()) ? loomHome : null;
        String configured = compile != null ? compile.getMavenHome() : mavenHome;
        String resolved = MavenHomeResolver.resolve(configured);

        CompileConfig config = toCompileConfig(compile);
        this.operations = new CompileAndDeployOperations(resolved, config);
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, resolvedMavenHome={}, fileBasePath={}, workspaceBasePath={}",
                compile != null && compile.isEnabled(), configured, resolved, this.fileBasePath,
                workspaceRoot());
    }

    // ==================== Tool Entry ====================

    private static String username(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object u = toolContext.getContext().get("username");
        return u == null ? null : u.toString();
    }

    // ==================== Username / Path ====================

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

    @Override
    public CompileAndDeployResult compileAndDeploy(Map<String, Object> params, ToolContext toolContext) {
        String username = username(toolContext);
        if (username == null) {
            return CompileAndDeployResult.fail(null, null, null, null, null, null,
                    List.of(), "无法获取用户名，请通过登录态调用");
        }

        Path workspaceBasePath = getCompileDeployWorkspaceDir(username);
        return operations.compileAndDeploy(workspaceBasePath, username, params);
    }

    /**
     * Per-user UPLOAD directory (i.e. where IUpload writes user-provided files
     * such as chat attachments and the file manager UI browses). This is what
     * {@link #getCompileDeployWorkspaceDir(String)} deliberately avoids — the
     * two spaces MUST be kept separate so compile-deploy workspaces don't
     * pollute the user's file listing and {@code rm -rf} on one space never
     * wipes the other.
     */
    Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    // ==================== Config Conversion ====================

    /**
     * Per-user COMPILE-DEPLOY WORKSPACE directory. Distinct from the upload dir
     * so users see only their files in the file manager. Resolution priority:
     * <ol>
     * <li>{@code compile.workspace-base-path} explicit yml config (relocate to
     * a separate disk/volume);</li>
     * <li>{@code {loomHome}/compile-deploy-workspaces} — derived from the
     * {@code loom-home} property;</li>
     * <li>legacy default {@code $user.home/.loom/compile-deploy-workspaces}.</li>
     * </ol>
     * All three coincide at {@code ~/.loom/compile-deploy-workspaces} when
     * nothing is configured, giving the user an obvious {@code rm -rf} target
     * per account:
     *
     * <pre>
     * rm -rf ~/.loom/compile-deploy-workspaces/&lt;username&gt; # clean up everything
     * </pre>
     */
    Path getCompileDeployWorkspaceDir(String username) {
        return workspaceRoot().resolve(username);
    }

    private Path workspaceRoot() {
        if (workspaceBasePath != null) {
            return Paths.get(workspaceBasePath);
        }
        if (loomHome != null) {
            return Paths.get(loomHome, "compile-deploy-workspaces");
        }
        return Paths.get(System.getProperty("user.home"), ".loom", "compile-deploy-workspaces");
    }
}
