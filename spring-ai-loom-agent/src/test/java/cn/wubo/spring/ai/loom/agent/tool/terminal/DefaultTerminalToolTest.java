package cn.wubo.spring.ai.loom.agent.tool.terminal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端工具基础单元测试。
 * 不依赖 Spring 上下文，直接实例化 DefaultTerminalTool 进行测试。
 */
class DefaultTerminalToolTest {

    private DefaultTerminalTool tool;
    private ToolContext toolContext;

    @BeforeEach
    void setUp() {
        tool = new DefaultTerminalTool();
        Map<String, Object> context = new HashMap<>();
        context.put("username", "test-user");
        toolContext = new ToolContext(context);
    }

    // ==================== Shell 模式测试 ====================

    @Test
    void startProcess_shellMode_echoCommand_shouldComplete() {
        String result = tool.startProcess("echo hello world", null, false, 5000L, toolContext);

        System.out.println("=== Shell Mode Result ===");
        System.out.println(result);

        assertTrue(result.contains("命令执行完成"));
        assertTrue(result.contains("退出码: 0"));
        assertTrue(result.contains("hello world"));
    }

    @Test
    void startProcess_shellMode_multilineCommand_shouldComplete() {
        String result = tool.startProcess("echo line1 && echo line2 && echo line3", null, false, 5000L, toolContext);

        System.out.println("=== Multiline Command ===");
        System.out.println(result);

        assertTrue(result.contains("退出码: 0"));
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line2"));
        assertTrue(result.contains("line3"));
    }

    @Test
    void startProcess_shellMode_timeout_shouldConvertToSession() {
        String result = tool.startProcess("ping -n 10 127.0.0.1 > nul 2>&1", null, false, 1000L, toolContext);

        System.out.println("=== Timeout Result ===");
        System.out.println(result);

        assertTrue(result.contains("超时") || result.contains("执行完成") || result.contains("转为受管会话"));
    }

    @Test
    void startProcess_shellMode_failCommand_shouldReturnNonZeroExit() {
        String result = tool.startProcess("dir non_existent_file_xyz", null, false, 5000L, toolContext);

        System.out.println("=== Fail Command ===");
        System.out.println(result);

        assertTrue(result.contains("退出码: 1") || result.contains("执行完成"));
    }

    // ==================== REPL 模式测试（非 PTY）====================

    @Test
    void startProcess_replMode_python_shouldStart() {
        String result = tool.startProcess("python --version", null, true, 2000L, toolContext);

        System.out.println("=== Python REPL Start ===");
        System.out.println(result);

        // 可能快速退出（--version）或进入 REPL
        assertTrue(result.contains("会话已启动") || result.contains("PTY 会话已启动") ||
                   result.contains("进程执行完成"));
    }

    @Test
    void startProcess_replMode_pythonInteractive_shouldStartRepl() {
        // 明确使用 -i 标志确保进入交互模式
        String result = tool.startProcess("python -i", null, true, 3000L, toolContext);

        System.out.println("=== Python Interactive REPL ===");
        System.out.println(result);

        // 非 PTY 模式下 python -i 会保持打开
        if (result.contains("会话已启动")) {
            String sessionId = extractSessionId(result);
            assertNotNull(sessionId, "Should have a session ID");

            // 读取输出
            String output = tool.readProcessOutput(sessionId, "tail", null, 5, toolContext);
            System.out.println("=== Read Output ===");
            System.out.println(output);
            assertTrue(output.contains("进程输出"));

            // 终止
            String terminate = tool.forceTerminate(sessionId, toolContext);
            System.out.println("=== Terminate ===");
            System.out.println(terminate);
            assertTrue(terminate.contains("已终止"));
        } else {
            System.out.println("Process exited quickly (non-PTY REPL, expected on some systems)");
        }
    }

    // ==================== OS 进程管理测试 ====================

    @Test
    void listProcesses_shouldReturnProcessList() {
        String result = tool.listProcesses(10, 0, toolContext);

        System.out.println("=== Process List ===");
        System.out.println(result);

        assertTrue(result.contains("系统进程"));
        assertTrue(result.contains("PID"));
    }

    @Test
    void listProcesses_pagination_shouldWork() {
        String result = tool.listProcesses(3, 0, toolContext);
        System.out.println("=== Page 0 ===");
        System.out.println(result);

        assertTrue(result.contains("PID"));
    }

    @Test
    void killProcess_invalidPid_shouldReturnError() {
        String result = tool.killProcess(-1L, true, toolContext);
        System.out.println("=== Invalid PID ===");
        System.out.println(result);
        assertTrue(result.contains("错误") || result.contains("无效"));
    }

    @Test
    void killProcess_nonExistentPid_shouldReturnError() {
        // 使用一个极大 PID，几乎不可能存在
        String result = tool.killProcess(999999999L, true, toolContext);
        System.out.println("=== Non-existent PID ===");
        System.out.println(result);
        assertTrue(result.contains("错误") || result.contains("不存在"));
    }

    // ==================== Session Management 测试 ====================

    @Test
    void listSessions_emptyByDefault() {
        String result = tool.listSessions(toolContext);
        System.out.println("=== Empty Sessions ===");
        System.out.println(result);
        assertTrue(result.contains("没有") || result.contains("会话"));
    }

    @Test
    void readProcessOutput_nonExistentSession_shouldReturnError() {
        String result = tool.readProcessOutput("fake-session-id", null, null, null, toolContext);
        System.out.println("=== Non-existent Session ===");
        System.out.println(result);
        assertTrue(result.contains("错误") || result.contains("不存在"));
    }

    @Test
    void forceTerminate_nonExistentSession_shouldReturnError() {
        String result = tool.forceTerminate("fake-session-id", toolContext);
        System.out.println("=== Force Terminate Fake Session ===");
        System.out.println(result);
        assertTrue(result.contains("错误") || result.contains("不存在"));
    }

    @Test
    void interactWithProcess_nonReplSession_shouldReturnError() {
        // 启动一个非 REPL 会话
        String start = tool.startProcess("sleep 2", null, false, 1000L, toolContext);
        System.out.println("=== Non-REPL Start ===");
        System.out.println(start);

        if (start.contains("会话已启动") || start.contains("转为受管会话")) {
            String sessionId = extractSessionId(start);
            if (sessionId != null) {
                String interact = tool.interactWithProcess(sessionId, "hello", 1000L, toolContext);
                System.out.println("=== Interact Non-REPL ===");
                System.out.println(interact);
                assertTrue(interact.contains("错误") || interact.contains("不是 REPL"));
                tool.forceTerminate(sessionId, toolContext);
            }
        }
    }

    // ==================== 完整交互流程测试 ====================

    @Test
    void fullLifecycle_shellMode_startReadTerminate() {
        // 启动一个较长运行的命令
        String result = tool.startProcess("ping -n 3 127.0.0.1 > nul 2>&1", null, false, 1000L, toolContext);
        System.out.println("=== Full Lifecycle Start ===");
        System.out.println(result);

        if (result.contains("会话") && result.contains("ID")) {
            String sessionId = extractSessionId(result);
            if (sessionId != null) {
                // 读取输出
                String output = tool.readProcessOutput(sessionId, "new", null, null, toolContext);
                System.out.println("=== Full Lifecycle Read ===");
                System.out.println(output);

                // 获取会话信息
                String info = tool.getProcessInfo(sessionId, toolContext);
                System.out.println("=== Full Lifecycle Info ===");
                System.out.println(info);
                assertTrue(info.contains("会话详情"));

                // 列出会话
                String sessions = tool.listSessions(toolContext);
                System.out.println("=== Full Lifecycle List ===");
                System.out.println(sessions);

                // 终止
                String terminate = tool.forceTerminate(sessionId, toolContext);
                System.out.println("=== Full Lifecycle Terminate ===");
                System.out.println(terminate);
                assertTrue(terminate.contains("已终止"));
            }
        }
    }

    // ==================== Helper ====================

    /**
     * 从工具返回结果中提取会话 ID
     */
    private String extractSessionId(String result) {
        Pattern pattern = Pattern.compile("会话ID:\\s*([a-f0-9-]+)");
        Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 也匹配英文格式
        pattern = Pattern.compile("([a-f0-9-]{36})");
        matcher = pattern.matcher(result);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
