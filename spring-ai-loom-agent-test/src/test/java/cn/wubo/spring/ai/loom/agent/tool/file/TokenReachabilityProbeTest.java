package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 疑点 A 实证：LLM 能否通过文件工具读到deleteConfirmToken 的值？
 *
 * <p><b>背景</b>：修复 token 回显泄漏后，仍保留公开默认值 {@code I_CONFIRM_DELETE}。
 * 若 LLM 能读到承载该值的文件，闸门依然可绕。本测试用真实工具方法（不 mock 路径解析）
 * 直接回答"读得到 / 读不到"，把推断变成实证。
 *
 * <p><b>攻击面枚举</b>：
 * <ol>
 *   <li>读源码树 {@code LoomAgentProperties.java}（默认值字面量所在）</li>
 *   <li>读运行时配置 {@code application.yml}</li>
 *   <li>绝对路径直达沙箱外</li>
 *   <li>{@code ..} 相对穿越</li>
 * </ol>
 *
 * <p>期望：全部被沙箱拦截。若任一放行，则闸门可绕、本次修复不充分。
 */
@DisplayName("疑点A 实证 — LLM 能否读到 deleteConfirmToken")
class TokenReachabilityProbeTest {

    private DefaultFileTool tool;
    private IFile fileService;
    private Path tmpRoot;
    private Path userTmp;

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        return new ToolContext(m);
    }

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(IFile.class);
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(null);

        userTmp = Files.createTempDirectory("loom-tokenprobe-");
        tmpRoot = userTmp.resolve("file");
        Files.createDirectories(tmpRoot);

        LoomAgentProperties.FileToolProperty cfg = new LoomAgentProperties.FileToolProperty();
        tool = new DefaultFileTool(fileService, userTmp.getParent().toString(), cfg);
    }

    private String read(String path, String username) {
        return tool.readTextFile(path, null, null, ctx(username));
    }

    // ==================== 攻击面 1: 源码树 ====================

    @Test
    @DisplayName("攻击面1 读项目源码树（默认值字面量所在）必须被拒")
    void attack1_sourceTreeBlocked() {
        String username = userTmp.getFileName().toString();
        String r = read("../../../../../../../developer/IdeaProjects/spring-ai-loom-agent/"
                + "spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/"
                + "LoomAgentProperties.java", username);

        assertFalse(r.contains("deleteConfirmToken"),
                "读到了含默认值的源码 —— 闸门可绕: " + r);
        assertTrue(r.contains("超出基础目录") || r.contains("错误"),
                "应以越权/错误告终，实际: " + r);
    }

    // ==================== 攻击面 2: 运行时配置 ====================

    @Test
    @DisplayName("攻击面2 读 classpath 外的 application.yml 必须被拒")
    void attack2_runtimeConfigBlocked() {
        String username = userTmp.getFileName().toString();
        String r = read("../../../../../../../developer/IdeaProjects/spring-ai-loom-agent/"
                + "spring-ai-loom-agent-test/src/main/resources/application.yml", username);

        assertFalse(r.contains("I_CONFIRM_DELETE"),
                "读到了含 token 的配置 —— 闸门可绕: " + r);
        assertTrue(r.contains("超出基础目录") || r.contains("错误"),
                "应以越权/错误告终，实际: " + r);
    }

    // ==================== 攻击面 3: 绝对路径 ====================

    @Test
    @DisplayName("攻击面3 绝对路径直达沙箱外必须被拒")
    void attack3_absolutePathBlocked() {
        String username = userTmp.getFileName().toString();
        String abs = System.getProperty("user.home") + "/.loom/../../etc/passwd";
        String r = read(abs, username);

        assertFalse(r.contains("root:"), "绝对路径读到了系统文件 —— 沙箱失效: " + r);
        assertTrue(r.contains("超出基础目录") || r.contains("错误"),
                "应以越权/错误告终，实际: " + r);
    }

    @Test
    @DisplayName("攻击面3b 绝对路径指向源码文件必须被拒")
    void attack3b_absoluteSourceBlocked() {
        String username = userTmp.getFileName().toString();
        String abs = System.getProperty("user.home")
                + "/.loom/users/x/file/../../../../../../../../developer/IdeaProjects/"
                + "spring-ai-loom-agent/spring-ai-loom-agent/src/main/java/cn/wubo/spring/"
                + "ai/loom/agent/model/LoomAgentProperties.java";
        String r = read(abs, username);

        assertFalse(r.contains("deleteConfirmToken"),
                "绝对路径读到了源码 —— 沙箱失效: " + r);
    }

    // ==================== 攻击面 4: .. 相对穿越 ====================

    @Test
    @DisplayName("攻击面4 .. 相对穿越必须被拒")
    void attack4_dotdotBlocked() {
        String username = userTmp.getFileName().toString();
        String r = read("../../../LoomAgentProperties.java", username);

        assertFalse(r.contains("deleteConfirmToken"), ".. 穿越读到了源码: " + r);
        assertTrue(r.contains("超出基础目录") || r.contains("错误"),
                "应以越权/错误告终，实际: " + r);
    }

    // ==================== 阳性对照：证明攻击路径本身是对的 ====================

    /**
     * 防"空转"哨兵：若上面的攻击用例某天变成"因为路径拼错而读不到"，它们会假绿。
     * 本例独立解析同一条相对路径，证明它<b>确实指向真实存在的源码文件</b>——
     * 于是"读不到"只能归因于沙箱拦截，而非路径无效。
     *
     * <p>注意：不依赖仓库被 checkout 在特定位置，只断言"从沙箱出发该相对路径能解析到
     * 一个存在的 .java 文件"。若开发机未检出本项目则跳过（assumption 前提不成立）。
     */
    @Test
    @DisplayName("阳性对照 攻击路径确实指向真实存在的源码文件（防空转）")
    void positiveControl_attackPathIsReal() throws IOException {
        String username = userTmp.getFileName().toString();
        Path sandbox = tmpRoot.toAbsolutePath().normalize();

        // 与 attack1 相同的相对路径
        Path resolved = sandbox.resolve(
                "../../../../../../../developer/IdeaProjects/spring-ai-loom-agent/"
                        + "spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/"
                        + "model/LoomAgentProperties.java").toAbsolutePath().normalize();

        // 前提：本机确实检出了该项目（否则本例无意义）
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(resolved),
                "本机未在预期位置检出源码，跳过阳性对照");

        // 该文件确实含 deleteConfirmToken 字面量 —— 即攻击目标是有意义的
        String content = Files.readString(resolved, StandardCharsets.UTF_8);
        assertTrue(content.contains("deleteConfirmToken"),
                "阳性对照失效：目标文件已不含 deleteConfirmToken，攻击面需重新评估");

        // 而工具调用它依然被拒 —— 拦截来自沙箱，不是路径无效
        String r = read("../../../../../../../developer/IdeaProjects/spring-ai-loom-agent/"
                + "spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/model/"
                + "LoomAgentProperties.java", username);
        assertFalse(r.contains("deleteConfirmToken"),
                "沙箱未拦住对真实源码的读取 —— 闸门可绕: " + r);
    }

    // ==================== 沙箱内正常读取仍可用（防闸门误伤） ====================

    @Test
    @DisplayName("对照组沙箱内正常读取仍成功")
    void controlInSandboxStillWorks() throws IOException {
        String username = userTmp.getFileName().toString();
        Files.writeString(tmpRoot.resolve("ok.txt"), "hello", StandardCharsets.UTF_8);

        String r = read("ok.txt", username);
        assertTrue(r.contains("hello"), "沙箱内正常读取被误伤: " + r);
    }
}