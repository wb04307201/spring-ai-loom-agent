package cn.wubo.spring.ai.loom.agent.tool.compile;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the compile-deploy workspace root resolution priority:
 * explicit {@code compile.workspace-base-path} &gt; {@code loom-home} derivation
 * &gt; legacy {@code user.home/.loom} default. All three coincide at
 * {@code ~/.loom/compile-deploy-workspaces} when nothing is configured
 * (backward compatible).
 */
class CompileWorkspaceRootResolutionTest {

    @Test
    void default_whenNothingConfigured_legacyUserHomePath() {
        DefaultCompileAndDeployTool tool =
                new DefaultCompileAndDeployTool(null, null, null, null, null);
        Path dir = tool.getCompileDeployWorkspaceDir("alice");
        assertThat(dir).isEqualTo(Paths.get(System.getProperty("user.home"),
                ".loom", "compile-deploy-workspaces", "alice"));
    }

    @Test
    void loomHomeOverride_derivesWorkspaceUnderIt() {
        DefaultCompileAndDeployTool tool =
                new DefaultCompileAndDeployTool(null, null, null, null, "/data/loom-home");
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get("/data/loom-home", "compile-deploy-workspaces", "alice"));
    }

    @Test
    void explicitWorkspaceBasePath_winsOverLoomHome() {
        DefaultCompileAndDeployTool tool =
                new DefaultCompileAndDeployTool(null, null, null, "/mnt/bigdisk/compile-ws", "/data/loom-home");
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get("/mnt/bigdisk/compile-ws", "alice"));
    }

    @Test
    void blankConfigValues_fallThroughToLegacyDefault() {
        DefaultCompileAndDeployTool tool =
                new DefaultCompileAndDeployTool(null, null, "  ", " ", "  ");
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get(System.getProperty("user.home"),
                        ".loom", "compile-deploy-workspaces", "alice"));
        // blank fileBasePath must NOT resurrect the cwd-relative .local default
        assertThat(tool.getUserFileDir("alice").toString())
                .startsWith(System.getProperty("user.home"))
                .doesNotContain(".local");
    }

    @Test
    void compilePropertyWorkspaceBasePath_flowsThroughPropertiesCtor() {
        cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties props =
                new cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties();
        props.setLoomHome("/data/loom-home");
        props.getCompile().setWorkspaceBasePath("/mnt/bigdisk/compile-ws");
        DefaultCompileAndDeployTool tool = new DefaultCompileAndDeployTool(props);
        assertThat(tool.getCompileDeployWorkspaceDir("alice"))
                .isEqualTo(Paths.get("/mnt/bigdisk/compile-ws", "alice"));
    }
}
