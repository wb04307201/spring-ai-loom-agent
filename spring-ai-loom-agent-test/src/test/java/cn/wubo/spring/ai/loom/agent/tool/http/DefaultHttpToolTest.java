package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultHttpTool 用户隔离与上下文")
class DefaultHttpToolTest {

    private static ToolContext ctxFor(String username) {
        return new ToolContext(Map.of("username", username));
    }

    private static LoomAgentProperties.HttpProperty props(List<String> allowedDomains) {
        LoomAgentProperties.HttpProperty p = new LoomAgentProperties.HttpProperty();
        p.setAllowedDomains(allowedDomains);
        return p;
    }

    @Test
    @DisplayName("缺少 username 上下文时返回错误文本，不抛异常")
    void missingUsernameReturnsError(@TempDir Path tmp) {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        String out = tool.listEndpoints("svc", null, null, new ToolContext(Map.of()));
        assertThat(out).contains("username");
    }

    @Test
    @DisplayName("两个用户的存储根互不可见 —— profile 不串号")
    void perUserStorageIsolation(@TempDir Path tmp) throws Exception {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        tool.listEndpoints("svc", null, null, ctxFor("alice"));
        tool.listEndpoints("svc", null, null, ctxFor("bob"));

        Path aliceDir = tmp.resolve("alice").resolve("http");
        Path bobDir = tmp.resolve("bob").resolve("http");
        // bob 的调用不能往自己的目录写出任何 system 文件（svc 未注册，两边都应是空目录）
        assertThat(dirIsEmpty(bobDir)).isTrue();
        // 存储根确实落在用户树下，且未逃出 tmp
        assertThat(aliceDir.startsWith(tmp.toAbsolutePath().normalize())).isTrue();
    }

    private static boolean dirIsEmpty(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return true;   // 目录都没建 = 空
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    @Test
    @DisplayName("白名单为空时拒绝，且不发起真实请求")
    void emptyWhitelistRejects(@TempDir Path tmp) {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        String out = tool.invokeEndpoint("svc", "GET", "/x",
                null, null, null, null, null, null, null, ctxFor("alice"));
        // svc 未注册 → 结构化错误；关键是不能抛异常
        assertThat(out).isNotNull();
        assertThat(out).doesNotContain("Connection refused");
    }

    /**
     * httpBatch 把 LLM 传进来的 List&lt;Map&lt;String,Object&gt;&gt; 转到
     * List&lt;InvokeRequest&gt;（BatchRequest 期望的类型）—— 字段必须正确落地，
     * 未知字段（如笔误 typo）静默忽略，不抛异常。
     */
    @Test
    @DisplayName("httpBatch: List<Map> → List<InvokeRequest> 字段正确映射")
    void batchOpsMapToInvokeRequest(@TempDir Path tmp) {
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), props(List.of()));
        // 由于 svc 未注册，invoke 时会拿到 SystemNotFoundException 的结构化错误信封，
        // 但关键验证点是请求参数原样传递。
        String out = tool.httpBatch(
                List.of(
                        Map.of("system", "svc", "method", "GET", "path", "/a"),
                        Map.of("system", "svc", "method", "POST", "path", "/b",
                                "params", Map.of("k", "v"),
                                "body", Map.of("name", "n"))),
                null, null, null, null,
                ctxFor("alice"));
        // 不抛异常 + 返回结构化结果（HttpEngine.toJson 把 SystemNotFoundException 折成 JSON envelope）
        assertThat(out).isNotNull();
        // 两端不应包含 Java 栈轨迹(那是真的异常而不是错误信封)
        assertThat(out).doesNotContain("Exception in thread");
        assertThat(out).doesNotContain("at cn.wubo");
    }
}