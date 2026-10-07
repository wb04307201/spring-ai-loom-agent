package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 删除确认闸门的信任边界回归锁。
 *
 * <p><b>缺陷背景</b>：{@code deleteFileOrDirectory} 在 confirm 校验失败时，把正确答案
 * 拼进错误消息返回（原文 {@code "请传入 confirm=\"" + token + "\""}）。该返回值在
 * Spring AI 中即 {@code ToolResponseMessage} 内容，<b>直接进入 LLM 上下文</b> ——
 * 于是"防 LLM 误删"的闸门在两次调用内必然失效：
 *
 * <pre>
 * 第 1 次：confirm=null  → 返回"请传入 confirm=I_CONFIRM_DELETE"
 * 第 2 次：confirm=I_CONFIRM_DELETE → 校验通过 → 删除
 * </pre>
 *
 * <p><b>本测试刻意使用非默认的自定义 token</b>（{@code S3CRET-CUSTOM-TOKEN-9x7q}）。
 * 若断言默认 token {@code I_CONFIRM_DELETE}，一个偷懒的实现有可能因为别处硬编码了
 * 默认串而假绿；用自定义值才能真正证明"配置中的令牌值没有被回显"。
 *
 * <p>covers: R1, R2, R3
 */
@DisplayName("删除确认闸门 — 令牌不得泄漏给 LLM")
class DeleteConfirmTokenLeakTest {

    private static final String CUSTOM_TOKEN = "S3CRET-CUSTOM-TOKEN-9x7q";

    private DefaultFileTool tool;
    private IFile fileService;
    private Path tmpRoot;
    private Path cleanupRoot;
    private String username;

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        return new ToolContext(m);
    }

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(IFile.class);
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(null);

        // 沙箱 = LoomPaths.userFileDir(usersBasePath, username) = {base}/{username}/file
        Path userTmp = Files.createTempDirectory("loom-delconfirm-test-");
        cleanupRoot = userTmp;
        username = userTmp.getFileName().toString();
        tmpRoot = userTmp.resolve("file");
        Files.createDirectories(tmpRoot);

        LoomAgentProperties.FileToolProperty cfg = new LoomAgentProperties.FileToolProperty();
        cfg.setDeleteConfirmToken(CUSTOM_TOKEN);
        tool = new DefaultFileTool(fileService, userTmp.getParent().toString(), cfg);

        Files.writeString(tmpRoot.resolve("victim.txt"), "important\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (cleanupRoot != null && Files.exists(cleanupRoot)) {
            try (java.util.stream.Stream<Path> walk = Files.walk(cleanupRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 清理尽力而为
                    }
                });
            }
        }
    }

    // ==================== R1: 失败路径不得回显令牌 ====================

    @Test
    @DisplayName("R1.1 confirm=null 时错误消息不含令牌")
    void confirmNull_messageDoesNotLeakToken() {
        String result = tool.deleteFileOrDirectory("victim.txt", null, ctx(username));

        assertFalse(result.contains(CUSTOM_TOKEN),
                "错误消息泄漏了令牌，LLM 一次失败即可获知: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("victim.txt")), "未确认不应删除文件");
    }

    @Test
    @DisplayName("R1.2 confirm 取值错误时错误消息不含令牌")
    void confirmWrong_messageDoesNotLeakToken() {
        String result = tool.deleteFileOrDirectory("victim.txt", "totally-wrong", ctx(username));

        assertFalse(result.contains(CUSTOM_TOKEN),
                "错误消息泄漏了令牌，LLM 一次失败即可获知: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("victim.txt")), "未确认不应删除文件");
    }

    @Test
    @DisplayName("R1.3 失败消息不提示 confirm 参数名（避免诱导 LLM 猜测该参数）")
    void confirmWrong_doesNotEchoConfirmParamName() {
        String result = tool.deleteFileOrDirectory("victim.txt", "totally-wrong", ctx(username));

        // 允许出现"确认"二字（这是合法指引），但不得出现可直接复用的 confirm="..." 形态
        assertFalse(result.contains("confirm=\""),
                "错误消息仍在回显 confirm=... 形态: " + result);
    }

    // ==================== R2: 正确令牌仍可删除（防回归） ====================

    @Test
    @DisplayName("R2.1 正确令牌仍能删除 —— 闸门未被误伤")
    void confirmCorrect_stillDeletes() {
        String result = tool.deleteFileOrDirectory("victim.txt", CUSTOM_TOKEN, ctx(username));

        assertTrue(result.contains("已删除"), "正确令牌应仍能删除: " + result);
        assertFalse(Files.exists(tmpRoot.resolve("victim.txt")));
    }

    // ==================== R3: core 层一致 ====================

    @Test
    @DisplayName("R3.1 core 层 FileOperations.delete 失败消息同样不含令牌")
    void coreLayer_messageDoesNotLeakToken() throws IOException {
        var cfg = new cn.wubo.loom.file.core.FileOperations.Config(
                10 * 1024 * 1024, 50 * 1024 * 1024, 5, 1000, 100,
                java.util.Set.of(".git"), CUSTOM_TOKEN);
        var ops = new cn.wubo.loom.file.core.FileOperations(cfg);

        String result = ops.delete(tmpRoot, "victim.txt", "totally-wrong");

        assertFalse(result.contains(CUSTOM_TOKEN),
                "core 层错误消息泄漏了令牌（MCP server 也走这条路径）: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("victim.txt")), "未确认不应删除文件");
    }

    @Test
    @DisplayName("R3.2 core 层正确令牌仍能删除")
    void coreLayer_confirmCorrectStillDeletes() throws IOException {
        var cfg = new cn.wubo.loom.file.core.FileOperations.Config(
                10 * 1024 * 1024, 50 * 1024 * 1024, 5, 1000, 100,
                java.util.Set.of(".git"), CUSTOM_TOKEN);
        var ops = new cn.wubo.loom.file.core.FileOperations(cfg);

        String result = ops.delete(tmpRoot, "victim.txt", CUSTOM_TOKEN);

        try {
            assertTrue(result.contains("已删除"), "core 层正确令牌应仍能删除: " + result);
        } catch (AssertionError e) {
            fail("core 层删除成功文案可能不同，实际返回: " + result);
        }
        assertFalse(Files.exists(tmpRoot.resolve("victim.txt")));
    }
}