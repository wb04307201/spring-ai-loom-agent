package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.loom.compile.core.CompileAndDeployResult;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.CompileProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultCompileAndDeployTool} 单元测试。
 * <p>
 * 重构后 DefaultCompileAndDeployTool 变成了委托给 loom-compile-core 的薄包装层。
 * 本测试仅覆盖仍在本模块可测试的行为：
 * <ul>
 * <li>username 缺失时立即返回失败</li>
 * <li>无效参数（空 gitUrl）立即返回失败</li>
 * <li>配置属性默认值</li>
 * <li>CompileAndDeployResult 工厂方法</li>
 * </ul>
 * 内部管线逻辑（writeDockerfile / findBuiltJar / dockerBuild 等）的测试
 * 已移至 loom-compile-core 模块。
 */
@DisplayName("DefaultCompileAndDeployTool 单元测试")
class DefaultCompileAndDeployToolTest {

    private DefaultCompileAndDeployTool tool;
    private CompileProperty compile;

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        if (username != null) {
            m.put("username", username);
        }
        return new ToolContext(m);
    }

    @BeforeEach
    void setUp() {
        compile = new CompileProperty();
        tool = new DefaultCompileAndDeployTool(compile, null, ".local/file");
    }

    @Test
    @DisplayName("compileAndDeploy 缺 gitUrl 立即失败")
    void compileAndDeploy_emptyGitUrl() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of(), ctx("alice"));
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("gitUrl"));
    }

    @Test
    @DisplayName("compileAndDeploy username 为空立即失败")
    void compileAndDeploy_emptyUsername() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of("gitUrl", "https://gitee.com/xxx/demo.git",
                        "port", 8080, "containerPort", 8080),
                ctx(null));
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("用户名"));
    }

    @Test
    @DisplayName("compileAndDeploy username 为空字符串同样视为缺失")
    void compileAndDeploy_blankUsername() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of("gitUrl", "https://gitee.com/xxx/demo.git",
                        "port", 8080, "containerPort", 8080),
                ctx(""));
        assertFalse(r.success());
    }

    @Test
    @DisplayName("LoomAgentProperties 持有 compile 配置，默认开启")
    void properties_defaultEnabled() {
        LoomAgentProperties p = new LoomAgentProperties();
        assertNotNull(p.getCompile());
        assertTrue(p.getCompile().isEnabled());
        assertNotNull(p.getCompile().getExtraRunArgs());
        assertTrue(p.getCompile().getExtraRunArgs().isEmpty());
    }

    @Test
    @DisplayName("CompileProperty 默认超时合理")
    void compileProperty_saneDefaults() {
        CompileProperty p = new CompileProperty();
        assertEquals(600000L, p.getMavenTimeoutMs());
        assertEquals(600000L, p.getDockerBuildTimeoutMs());
        assertEquals(60000L, p.getDockerRunTimeoutMs());
        assertEquals(60000L, p.getHealthCheckMaxWaitMs());
        assertEquals(2000L, p.getHealthCheckIntervalMs());
        assertFalse(p.isKeepWorkspace());
    }

    @Test
    @DisplayName("CompileProperty.imageTemplates 默认预置 java17/java21/nginx/python3")
    void imageTemplates_defaultIncludesCommonAliases() {
        CompileProperty p = new CompileProperty();
        assertNotNull(p.getImageTemplates(), "imageTemplates 不应为 null");
        assertTrue(p.getImageTemplates().containsKey("java17"), "缺 java17 模板");
        assertTrue(p.getImageTemplates().containsKey("java21"), "缺 java21 模板");
        assertTrue(p.getImageTemplates().containsKey("nginx"), "缺 nginx 模板");
        assertTrue(p.getImageTemplates().containsKey("python3"), "缺 python3 模板");
    }

    @Test
    @DisplayName("CompileAndDeployResult ok/fail 工厂方法")
    void resultFactories() {
        CompileAndDeployResult ok = CompileAndDeployResult.ok("/w", "repo", "main", "img", "ctr", 8080,
                "http://localhost:8080", "/", List.of("step1"));
        assertTrue(ok.success());
        assertEquals("http://localhost:8080", ok.accessUrl());
        assertNull(ok.errorMessage());

        CompileAndDeployResult fail = CompileAndDeployResult.fail("/w", "repo", "img", "ctr", 8080, "/",
                List.of("step1"), "boom");
        assertFalse(fail.success());
        assertEquals("boom", fail.errorMessage());
        assertNull(fail.accessUrl());
    }

    @Test
    @DisplayName("CompileProperty 已删除 defaultPort 字段")
    void compileProperty_noDefaultPortField() {
        assertThatThrownBy(() -> LoomAgentProperties.CompileProperty.class.getDeclaredField("defaultPort"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    @Test
    @DisplayName("CompileProperty 已删除 baseImage 字段")
    void compileProperty_noBaseImageField() {
        assertThatThrownBy(() -> LoomAgentProperties.CompileProperty.class.getDeclaredField("baseImage"))
                .isInstanceOf(NoSuchFieldException.class);
    }
}
