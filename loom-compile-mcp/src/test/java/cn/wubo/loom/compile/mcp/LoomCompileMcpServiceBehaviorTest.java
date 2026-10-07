package cn.wubo.loom.compile.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * loom-compile-mcp 唯一工具 {@code compile_and_deploy} 的<b>行为</b>回归锁。
 *
 * <p><b>与 {@link LoomCompileMcpSchemaRequiredTest} 的分工</b>:后者锁 MCP schema 契约
 * (required 数组),本类锁"入参怎么落到 {@code CompileAndDeployOperations}、失败怎么呈现"。
 *
 * <p><b>为什么只测参数校验,不测真实部署</b>:该工具的完整链路是
 * {@code git clone → 构建 → docker build → docker run → 健康检查} ——
 * 会<b>真的 clone 远程仓库、真的跑 Docker、真的起容器</b>。那属于 IT 的范畴
 * (且需要 Docker daemon + 网络),不该进单测。
 * 本类锁的是部署<b>之前</b>就能确定的那部分契约:缺参数时必须 fail-closed、
 * 不得触发任何外部副作用、错误消息可读。
 *
 * <p><b>fail-closed 契约</b>(读 {@code CompileAndDeployOperations:301-313} 确认):
 * {@code gitUrl} / {@code port} / {@code containerPort} 三者缺任一 ⇒ 立即返回
 * {@code CompileAndDeployResult.fail(...)}，<b>不 clone、不 build、不起容器</b>。
 */
@DisplayName("loom-compile-mcp 工具行为")
class LoomCompileMcpServiceBehaviorTest {

    @TempDir
    Path tmp;

    private Path base;
    private LoomCompileMcpService service;

    @BeforeEach
    void setUp() throws Exception {
        base = tmp.resolve("base");
        Files.createDirectories(base);
        LoomCompileMcpProperties props = new LoomCompileMcpProperties();
        props.setBasePath(base.toString());
        service = new LoomCompileMcpService(props);
    }

    private Map<String, Object> params(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ==================== 必填参数校验(fail-closed) ====================

    @Test
    @DisplayName("缺 gitUrl:返回部署失败 + 明确错误,不 clone 不 build")
    void missingGitUrlFailsClosed() {
        String out = service.compileAndDeploy(params("port", "8080", "containerPort", "8080"));
        assertThat(out).contains("部署失败").contains("gitUrl is required");
    }

    @Test
    @DisplayName("缺 port:返回部署失败 + 明确错误")
    void missingPortFailsClosed() {
        String out = service.compileAndDeploy(
                params("gitUrl", "https://example.invalid/x.git", "containerPort", "8080"));
        assertThat(out).contains("部署失败").contains("port is required");
    }

    @Test
    @DisplayName("缺 containerPort:返回部署失败 + 明确错误")
    void missingContainerPortFailsClosed() {
        String out = service.compileAndDeploy(
                params("gitUrl", "https://example.invalid/x.git", "port", "8080"));
        assertThat(out).contains("部署失败").contains("containerPort is required");
    }

    @Test
    @DisplayName("gitUrl 为空白字符串:同样 fail-closed")
    void blankGitUrlFailsClosed() {
        String out = service.compileAndDeploy(
                params("gitUrl", "   ", "port", "8080", "containerPort", "8080"));
        assertThat(out).contains("部署失败").contains("gitUrl is required");
    }

    @Test
    @DisplayName("参数校验失败时:不在 basePath 下创建任何 workspace")
    void noWorkspaceCreatedOnValidationFailure() {
        service.compileAndDeploy(params("port", "8080", "containerPort", "8080"));
        try (var entries = Files.list(base)) {
            assertThat(entries.count()).as("校验失败不应留下 workspace 目录").isZero();
        } catch (Exception e) {
            throw new AssertionError("列目录失败", e);
        }
    }

    // ==================== 参数别名与容错 ====================

    @Test
    @DisplayName("snake_case 别名(git_url)被识别 —— 不误报缺 gitUrl")
    void acceptsSnakeCaseAlias() {
        // 用 snake_case 提供全部必填项:若别名不被识别,会报 gitUrl/port 缺失
        String out = service.compileAndDeploy(params(
                "git_url", "https://example.invalid/x.git",
                "port", "8080", "container_port", "8080"));
        // 不再报 "is required" ⇒ 别名生效;后续会因网络/Docker 失败,那是预期外路径
        assertThat(out).doesNotContain("gitUrl is required")
                .doesNotContain("port is required")
                .doesNotContain("containerPort is required");
    }

    @Test
    @DisplayName("__user 缺省:不影响参数校验(走 anonymous 兜底)")
    void defaultsUserToAnonymous() {
        String out = service.compileAndDeploy(params("port", "8080", "containerPort", "8080"));
        assertThat(out).contains("部署失败");
        // 校验失败发生在 user 解析之后,故 user 分支不会 NPE
    }

    // ==================== null 入参 ====================

    @Test
    @DisplayName("params 为 null:当前实现抛 NPE(记录真实行为)")
    void nullParamsThrowsNpe() {
        // schema 侧 params 是必填,故正常客户端路径不会传 null。
        // 这里锁当前真实行为,让"若将来改成友好报错"能被测试察觉。
        assertThatThrownBy(() -> service.compileAndDeploy(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================== 结果呈现 ====================

    @Test
    @DisplayName("失败结果的输出格式:❌ 前缀 + 错误行,可被 LLM 直接读懂")
    void failureOutputIsLlmReadable() {
        String out = service.compileAndDeploy(params("port", "8080", "containerPort", "8080"));
        assertThat(out).startsWith("❌").contains("错误:");
    }
}
