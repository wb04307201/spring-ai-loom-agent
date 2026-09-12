package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the user-tree derivation contract in {@link LoomPaths} (single source of
 * truth) and its wiring through {@link DefaultCompileAndDeployTool}:
 * file sandbox and compile workspaces are siblings under
 * {@code {usersBasePath}/{username}}, username is sanitized, and blank config
 * falls back to the absolute {@code ~/.loom/users} default (never cwd-relative).
 */
class CompileWorkspaceRootResolutionTest {

    @Test
    void default_whenNothingConfigured_derivesUnderUserHomeUsers() {
        DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(null, null, null);
        assertThat(tool.getCompileDeployWorkspaceDir("alice")).isEqualTo(
                Paths.get(System.getProperty("user.home"), ".loom", "users", "alice", "compile-workspaces"));
        assertThat(tool.getUserFileDir("alice")).isEqualTo(
                Paths.get(System.getProperty("user.home"), ".loom", "users", "alice", "file"));
    }

    @Test
    void explicitUsersBasePath_derivesBothDirsUnderIt() {
        DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(null, null, "/data/loom-users");
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get("/data/loom-users", "alice", "compile-workspaces"));
        assertThat(tool.getUserFileDir("alice"))
                .isEqualTo(Paths.get("/data/loom-users", "alice", "file"));
    }

    @Test
    void blankConfigValues_fallThroughToAbsoluteDefault() {
        DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(null, null, "  ");
        Path dir = tool.getCompileDeployWorkspaceDir("alice");
        assertThat(dir.toString())
                .startsWith(System.getProperty("user.home"))
                .doesNotContain(".local");
        assertThat(tool.getUserFileDir("alice").toString())
                .startsWith(System.getProperty("user.home"))
                .doesNotContain(".local");
    }

    @Test
    void usernameSanitized_preventsTraversalViaDirName() {
        // A hostile username must never escape the users root: separators are
        // squashed to '_', so "../evil" becomes the literal dir name ".._evil"
        // and the resolved path still starts with the base.
        Path hostile = LoomPaths.userRoot("/base", "../evil");
        assertThat(hostile).isEqualTo(Paths.get("/base", ".._evil"));
        assertThat(hostile.normalize().startsWith(Paths.get("/base"))).isTrue();
        assertThat(LoomPaths.userFileDir("/base", "a/b")).isEqualTo(Paths.get("/base", "a_b", "file"));
        assertThat(LoomPaths.userRoot("/base", null)).isEqualTo(Paths.get("/base", "anonymous"));
        assertThat(LoomPaths.userRoot("/base", " ")).isEqualTo(Paths.get("/base", "anonymous"));
    }

    @Test
    void usersBasePath_flowsThroughPropertiesCtor() {
        LoomAgentProperties props = new LoomAgentProperties();
        props.setUsersBasePath("/data/loom-users");
        DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get("/data/loom-users", "alice", "compile-workspaces"));
    }
}
