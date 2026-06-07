package cn.wubo.spring.ai.loom.agent.tool.terminal;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 终端/进程/会话管理工具接口。
 * <p>
 * 提供 9 个工具，分为三大类：
 * <ul>
 * <li>终端会话管理与交互：startProcess, interactWithProcess, readProcessOutput, forceTerminate</li>
 * <li>终端会话状态查看：listSessions, getProcessInfo, sendSignal</li>
 * <li>操作系统级进程管理：listProcesses, killProcess</li>
 * </ul>
 */
public interface ITerminalTool extends IEmbedTool {

    /**
     * 启动一个终端进程或 REPL 会话
     */
    String startProcess(String command, String workingDir, Boolean repl, Long timeout, ToolContext toolContext);

    /**
     * 向运行中的 REPL 进程发送输入并等待响应
     */
    String interactWithProcess(String sessionId, String input, Long timeout, ToolContext toolContext);

    /**
     * 读取运行中进程的输出内容
     */
    String readProcessOutput(String sessionId, String mode, Integer position, Integer lines, ToolContext toolContext);

    /**
     * 强制终止一个受管终端会话
     */
    String forceTerminate(String sessionId, ToolContext toolContext);

    /**
     * 列出所有受管终端会话
     */
    String listSessions(ToolContext toolContext);

    /**
     * 获取单个会话的详细信息
     */
    String getProcessInfo(String sessionId, ToolContext toolContext);

    /**
     * 向会话发送控制信号（如 Ctrl+C、Ctrl+D）
     */
    String sendSignal(String sessionId, String signal, ToolContext toolContext);

    /**
     * 列出操作系统的所有进程
     */
    String listProcesses(Integer maxResults, Integer page, ToolContext toolContext);

    /**
     * 根据 PID 终止系统进程
     */
    String killProcess(Long pid, Boolean force, ToolContext toolContext);
}
