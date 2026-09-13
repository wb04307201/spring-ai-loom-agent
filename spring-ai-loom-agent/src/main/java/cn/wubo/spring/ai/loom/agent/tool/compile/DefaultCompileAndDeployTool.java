package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.loom.compile.core.CompileAndDeployOperations;
import cn.wubo.loom.compile.core.CompileAndDeployResult;
import cn.wubo.loom.compile.core.CompileConfig;
import cn.wubo.loom.compile.core.ImageTemplate;
import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.maven.MavenHomeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ICompileAndDeployTool} 的默认实现：委托给 {@link CompileAndDeployOperations}。
 * <p>
 * 本类是薄包装层，负责：
 * <ul>
 * <li>从 {@link ToolContext} 提取 username，经 {@link LoomPaths} 派生该用户的
 * workspace 目录（{@code {usersBasePath}/{username}/compile-workspaces}）</li>
 * <li>将 {@link LoomAgentProperties.CompileProperty} 转换为 {@link CompileConfig}</li>
 * </ul>
 * 所有管线逻辑（clone/build/docker/health/process management）均位于 {@code loom-compile-core}。
 */
public class DefaultCompileAndDeployTool implements ICompileAndDeployTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultCompileAndDeployTool.class);

    private final CompileAndDeployOperations operations;
    private final String usersBasePath;

    public DefaultCompileAndDeployTool(LoomAgentProperties properties) {
        this(properties.getCompile(), properties.getMaven() != null ? properties.getMaven().getMavenHome() : null,
                properties.getUsersBasePath());
    }

    /**
     * 供测试直接注入
     */
    DefaultCompileAndDeployTool(LoomAgentProperties.CompileProperty compile, String mavenHome, String usersBasePath) {
        this.usersBasePath = LoomPaths.orDefaultUsersBase(usersBasePath);
        String configured = compile != null ? compile.getMavenHome() : mavenHome;
        String resolved = MavenHomeResolver.resolve(configured);

        CompileConfig config = toCompileConfig(compile);
        this.operations = new CompileAndDeployOperations(resolved, config);
        log.info("CompileAndDeployTool initialized: enabled={}, mavenHome={}, resolvedMavenHome={}, usersBasePath={}",
                compile != null && compile.isEnabled(), configured, resolved, this.usersBasePath);
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
     * Per-user UPLOAD directory ({@code {usersBasePath}/{username}/file}) — where
     * IUpload writes user-provided files and the file manager UI browses. This is
     * what {@link #getCompileDeployWorkspaceDir(String)} deliberately avoids: the
     * two spaces are SIBLINGS under the user root, so compile-deploy workspaces
     * don't pollute the user's file listing, the file-tool sandbox can never
     * reach them, and {@code rm -rf} on one space never wipes the other.
     */
    Path getUserFileDir(String username) {
        return LoomPaths.userFileDir(usersBasePath, username);
    }

    // ==================== Config Conversion ====================

    /**
     * Per-user COMPILE-DEPLOY WORKSPACE directory:
     * {@code {usersBasePath}/{username}/compile-workspaces/}. Each run creates a
     * {@code compile-deploy-<user>-<ts>-<uuid8>} subdir inside (naming owned by
     * {@code CompileAndDeployOperations}). Sibling of the upload dir, never under
     * it. The whole user tree is one {@code rm -rf} away:
     *
     * <pre>
     * rm -rf ~/.loom/users/&lt;username&gt; # clean up everything for that user
     * </pre>
     */
    Path getCompileDeployWorkspaceDir(String username) {
        return LoomPaths.userCompileWorkspacesDir(usersBasePath, username);
    }
}
