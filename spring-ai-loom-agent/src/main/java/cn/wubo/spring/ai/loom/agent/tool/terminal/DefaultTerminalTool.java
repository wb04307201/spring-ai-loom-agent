package cn.wubo.spring.ai.loom.agent.tool.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 终端/进程/会话管理工具默认实现。
 * <p>
 * 支持两种进程启动模式：
 * <ul>
 * <li>PTY 模式：通过 pty4j 伪终端启动 REPL，支持真正的交互（Ctrl+C 等信号）</li>
 * <li>标准模式：通过 ProcessBuilder 启动，适用于一次性命令或无 PTY 环境</li>
 * </ul>
 * <p>
 * pty4j 作为可选依赖，在编译时存在但运行时可能缺失。
 * 所有 PTY 操作通过反射调用，确保无 pty4j 时仍能正常启动。
 */
public class DefaultTerminalTool implements ITerminalTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultTerminalTool.class);

    /** pty4j 类是否可用（编译时存在） */
    private static final boolean PTY4J_PRESENT;

    static {
        boolean present = false;
        try {
            Class.forName("com.pty4j.PtyProcessBuilder");
            Class.forName("com.pty4j.PtyProcess");
            present = true;
        } catch (ClassNotFoundException e) {
            // pty4j not on classpath
        }
        PTY4J_PRESENT = present;
        log.debug("PTY4J classpath presence: {}", PTY4J_PRESENT);
    }

    private final ConcurrentHashMap<String, ProcessSession> sessions = new ConcurrentHashMap<>();
    private final String baseFileDir;
    private final boolean ptyAvailable;

    public DefaultTerminalTool() {
        this.baseFileDir = ".local/file";
        this.ptyAvailable = detectPtyAvailability();
        log.info("TerminalTool initialized: PTY support = {}", ptyAvailable);
    }

    /**
     * 检测 PTY 是否可用（需要类存在 + 能实际创建 PTY 进程）
     */
    private boolean detectPtyAvailability() {
        if (!PTY4J_PRESENT) {
            log.debug("PTY not available: pty4j classes not on classpath");
            return false;
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd = os.contains("win") ? new String[]{"cmd.exe"} : new String[]{"/bin/sh"};

            Object builder = Class.forName("com.pty4j.PtyProcessBuilder")
                    .getConstructor(String[].class)
                    .newInstance((Object) cmd);

            builder.getClass().getMethod("setDirectory", String.class)
                    .invoke(builder, System.getProperty("user.dir"));

            Object pty = builder.getClass().getMethod("start").invoke(builder);

            // 检查是否存活
            Process proc = (Process) pty;
            boolean alive = proc.isAlive();

            proc.destroy();
            proc.waitFor(2, TimeUnit.SECONDS);

            return alive;
        } catch (Exception e) {
            log.debug("PTY not available: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Tool Methods ====================

    @Tool(description = "启动一个终端进程或 REPL 会话。支持两种模式：Shell 模式（一次性命令，如 ls、cat）和 REPL 模式（长交互会话，如 python、node）。REPL 模式优先使用伪终端（PTY），支持真正的交互。返回会话ID、PID、初始输出。")
    @Override
    public String startProcess(
            @ToolParam(description = "要执行的命令。Shell 模式：任意 shell 命令（如 'ls -la', 'cat file.txt'）。REPL 模式：解释器命令（如 'python', 'node', 'bash'）") String command,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "是否为 REPL 模式。true 表示长交互会话（保持打开等待输入），false 或省略表示一次性命令（执行完即退出）", required = false) Boolean repl,
            @ToolParam(description = "等待超时时间（毫秒），默认 30000ms。超时后如果进程仍在运行，则认为它是长会话并返回 sessionId", required = false) Long timeout,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        if (username == null || username.isEmpty()) {
            return "错误：无法获取用户名";
        }

        boolean isRepl = repl != null && repl;
        long waitTimeout = timeout != null && timeout > 0 ? timeout : 30000L;
        String sessionId = UUID.randomUUID().toString();

        // 确定工作目录
        Path workPath = resolveWorkingDir(workingDir, username);
        try {
            Files.createDirectories(workPath);
        } catch (IOException e) {
            return "创建工作目录失败：" + e.getMessage();
        }

        try {
            if (isRepl && ptyAvailable) {
                return startPtyProcess(sessionId, username, command, workPath, waitTimeout);
            } else if (isRepl) {
                return startNonShellProcess(sessionId, username, command, workPath, waitTimeout);
            } else {
                return startShellProcess(sessionId, username, command, workPath, waitTimeout);
            }
        } catch (IOException e) {
            return "启动进程失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "启动进程被中断：" + e.getMessage();
        }
    }

    @Tool(description = "向运行中的 REPL 进程发送输入并等待响应。适用于与 Python、Node.js、Bash 等交互式会话逐步通信。PTY 模式下支持完整的终端交互体验。")
    @Override
    public String interactWithProcess(
            @ToolParam(description = "会话ID（由 startProcess 返回）") String sessionId,
            @ToolParam(description = "要发送的输入内容（如 'print(\"hello\")', 'import os'）。末尾会自动追加换行符") String input,
            @ToolParam(description = "等待响应超时时间（毫秒），默认 10000ms", required = false) Long timeout,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        ProcessSession session = validateSession(sessionId, username);
        if (session == null) return sessionNotFoundMessage(sessionId);

        if (!session.isRepl()) {
            return "错误：会话 " + sessionId + " 不是 REPL 模式，无法交互。请使用 readProcessOutput 读取输出，或使用 forceTerminate 终止会话。";
        }
        if (!session.isAlive()) {
            return String.format("错误：会话 %s 的进程已退出（退出码：%d），无法交互。", sessionId, session.exitValue());
        }

        long waitTimeout = timeout != null && timeout > 0 ? timeout : 10000L;

        try {
            int beforeIndex = session.getOutputLength();

            // 发送输入（自动追加换行）
            String inputWithNewline = input.endsWith("\n") ? input : input + "\n";
            session.getStdin().write(inputWithNewline.getBytes());
            session.getStdin().flush();

            // 等待响应
            session.setBlocked(true);
            try {
                boolean completed = session.getProcess().waitFor(waitTimeout, TimeUnit.MILLISECONDS);
                if (completed) {
                    session.setState(SessionState.COMPLETED);
                    String newOutput = session.getOutputFrom(beforeIndex);
                    return String.format("""
                            【进程已退出】
                            会话ID: %s
                            退出码: %d
                            新输出:
                            %s
                            """, sessionId, session.exitValue(),
                            newOutput.isEmpty() ? "(无新输出)" : newOutput);
                }
            } finally {
                session.setBlocked(false);
            }

            String newOutput = session.getOutputFrom(beforeIndex);
            session.setState(SessionState.RUNNING);

            return String.format("""
                    【交互响应】
                    会话ID: %s
                    发送输入: %s
                    新输出:
                    %s
                    """, sessionId, input,
                    newOutput.isEmpty() ? "(等待更多输出... 可使用 readProcessOutput 的 new 模式继续读取)" : newOutput);

        } catch (IOException e) {
            return "发送输入失败：" + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "交互被中断：" + e.getMessage();
        }
    }

    @Tool(description = "读取运行中进程的输出内容。支持三种模式：new（自上次读取后的新内容，默认）、tail（最后 N 行）、absolute（从字符位置 N 开始读取）。")
    @Override
    public String readProcessOutput(
            @ToolParam(description = "会话ID") String sessionId,
            @ToolParam(description = "读取模式：new（自上次读取后的新内容，默认）、tail（最后 N 行）、absolute（从指定字符位置开始）", required = false) String mode,
            @ToolParam(description = "绝对字符位置（仅在 mode=absolute 时使用，从 0 开始）", required = false) Integer position,
            @ToolParam(description = "行数（仅在 mode=tail 时使用，默认 50 行）", required = false) Integer lines,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        ProcessSession session = validateSession(sessionId, username);
        if (session == null) return sessionNotFoundMessage(sessionId);

        String readMode = mode != null && !mode.isBlank() ? mode.toLowerCase() : "new";
        String output = switch (readMode) {
            case "tail" -> {
                int lineCount = lines != null && lines > 0 ? lines : 50;
                yield session.getTailOutput(lineCount);
            }
            case "absolute" -> {
                int pos = position != null && position >= 0 ? position : 0;
                yield session.getOutputFrom(pos);
            }
            default -> {
                int lastIndex = session.getLastReadIndex().get();
                String out = session.getOutputFrom(lastIndex);
                session.getLastReadIndex().set(session.getOutputLength());
                yield out;
            }
        };

        String stateInfo = !session.isAlive() ?
                String.format("\n【注意】进程已退出，退出码：%d", session.exitValue()) : "";

        return String.format("""
                【进程输出】
                会话ID: %s
                读取模式: %s%s
                %s
                """, sessionId, readMode, stateInfo,
                output.isEmpty() ? "(无输出)" : output);
    }

    @Tool(description = "强制终止一个受管终端会话。会强制杀死进程并从会话列表中移除。适用于进程卡死、陷入死循环或不再需要的场景。")
    @Override
    public String forceTerminate(
            @ToolParam(description = "会话ID") String sessionId,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        ProcessSession session = validateSession(sessionId, username);
        if (session == null) return sessionNotFoundMessage(sessionId);

        return doForceTerminate(session);
    }

    @Tool(description = "列出所有当前用户的活动终端会话。显示每个会话的会话ID、PID、状态（运行中/阻塞/已完成）、是否REPL模式、持续时间和输出缓冲大小。")
    @Override
    public String listSessions(ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");

        // 清理已完成的会话
        cleanupCompletedSessions();

        List<ProcessSession> userSessions = sessions.values().stream()
                .filter(s -> username.equals(s.getUsername()))
                .sorted(Comparator.comparing(ProcessSession::getStartTime).reversed())
                .toList();

        if (userSessions.isEmpty()) {
            return "当前没有活跃的终端会话";
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【活跃终端会话】共 %d 个%n%n", userSessions.size()));

        for (ProcessSession session : userSessions) {
            sb.append(String.format("会话ID: %s%n", session.getSessionId()))
                    .append(String.format("  命令: %s%n", session.getCommand()))
                    .append(String.format("  PID: %d%n", session.getPid()))
                    .append(String.format("  状态: %s%s%n", session.getState(),
                            !session.isAlive() && session.getState() == SessionState.RUNNING ?
                                    " (进程已退出，退出码: " + session.exitValue() + ")" : ""))
                    .append(String.format("  REPL模式: %s%n", session.isRepl() ? "是" : "否"))
                    .append(String.format("  PTY模式: %s%n", session.isPty() ? "是" : "否"))
                    .append(String.format("  启动时间: %s%n", session.getStartTime().format(formatter)))
                    .append(String.format("  持续时间: %d秒%n", session.getDurationSeconds()))
                    .append(String.format("  输出缓冲: %d 字符%n", session.getOutputLength()))
                    .append("---\n");
        }

        return sb.toString();
    }

    @Tool(description = "获取单个终端会话的详细信息。包括完整输出内容、进程状态、工作目录、PTY 模式等。比 listSessions 提供更详细的信息。")
    @Override
    public String getProcessInfo(
            @ToolParam(description = "会话ID") String sessionId,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        ProcessSession session = validateSession(sessionId, username);
        if (session == null) return sessionNotFoundMessage(sessionId);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        String fullOutput = session.getFullOutput();

        return String.format("""
                【会话详情】
                会话ID: %s
                命令: %s
                PID: %d
                状态: %s
                进程存活: %s
                REPL模式: %s
                PTY模式: %s
                工作目录: %s
                启动时间: %s
                持续时间: %d秒

                【完整输出】(%d 字符)
                %s
                """,
                session.getSessionId(),
                session.getCommand(),
                session.getPid(),
                session.getState(),
                session.isAlive(),
                session.isRepl(),
                session.isPty(),
                session.getWorkingDir(),
                session.getStartTime().format(formatter),
                session.getDurationSeconds(),
                fullOutput.length(),
                fullOutput.isEmpty() ? "(无输出)" : fullOutput);
    }

    @Tool(description = "向终端会话发送控制信号。PTY 模式支持：interrupt（Ctrl+C 中断当前操作）、eof（Ctrl+D 发送 EOF）、quit（Ctrl+\\ 退出）。非 PTY 模式下仅 interrupt 可用（通过 destroy 终止进程）。")
    @Override
    public String sendSignal(
            @ToolParam(description = "会话ID") String sessionId,
            @ToolParam(description = "信号类型：interrupt（Ctrl+C，中断当前操作但不终止进程）、eof（Ctrl+D，发送 EOF）、quit（Ctrl+\\，退出）") String signal,
            ToolContext toolContext) {

        String username = (String) toolContext.getContext().get("username");
        ProcessSession session = validateSession(sessionId, username);
        if (session == null) return sessionNotFoundMessage(sessionId);

        if (!session.isAlive()) {
            return String.format("错误：会话 %s 的进程已退出（退出码：%d），无法发送信号。", sessionId, session.exitValue());
        }

        String signalLower = signal.toLowerCase();
        boolean sent = session.sendSignal(signalLower);

        String signalDescription = switch (signalLower) {
            case "interrupt", "ctrl-c", "sigint" -> "Ctrl+C (中断)";
            case "eof", "ctrl-d", "eot" -> "Ctrl+D (EOF)";
            case "quit", "ctrl-\\", "sigquit" -> "Ctrl+\\ (退出)";
            default -> signal;
        };

        if (sent) {
            return String.format("""
                    【信号已发送】
                    会话ID: %s
                    信号: %s
                    注意：进程可能需要短暂时间响应信号，请使用 readProcessOutput 查看结果。
                    """, sessionId, signalDescription);
        } else {
            if ("interrupt".equals(signalLower)) {
                return String.format("""
                        【信号发送失败 - 非 PTY 模式】
                        会话ID: %s
                        信号: %s
                        原因：此会话未使用 PTY 启动，无法发送终端控制信号。
                        建议：如需 Ctrl+C 功能，请使用 startProcess 重新以 REPL 模式启动进程。
                        替代方案：使用 forceTerminate 可强制终止此会话（但会结束进程）。
                        """, sessionId, signalDescription);
            }
            return String.format("""
                    【信号发送失败】
                    会话ID: %s
                    信号: %s
                    原因：此会话不支持该信号类型。PTY 模式支持 interrupt/eof/quit。
                    """, sessionId, signalDescription);
        }
    }

    @Tool(description = "列出操作系统中所有正在运行的进程。类似于 Linux 的 ps 或 Windows 的任务管理器。返回 PID、命令名称、CPU 时间和启动时间。支持分页查询。")
    @Override
    public String listProcesses(
            @ToolParam(description = "每页最大返回结果数，默认 50，最大 200", required = false) Integer maxResults,
            @ToolParam(description = "页码（从 0 开始），默认 0", required = false) Integer page,
            ToolContext toolContext) {

        int limit = maxResults != null && maxResults > 0 ? Math.min(maxResults, 200) : 50;
        int offset = page != null && page > 0 ? page : 0;

        try {
            List<ProcessHandle> allProcesses = ProcessHandle.allProcesses()
                    .sorted(Comparator.comparingLong(p ->
                            -p.info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L)))
                    .toList();

            int total = allProcesses.size();
            int fromIndex = Math.min(offset * limit, total);
            int toIndex = Math.min(fromIndex + limit, total);

            if (fromIndex >= total) {
                return String.format("没有更多进程（共 %d 个进程，已超出范围）", total);
            }

            List<ProcessHandle> pageProcesses = allProcesses.subList(fromIndex, toIndex);
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【系统进程】第 %d 页，共 %d 个进程（每页 %d 个）%n%n",
                    offset, total, limit));

            for (ProcessHandle ph : pageProcesses) {
                ProcessHandle.Info info = ph.info();
                sb.append(String.format("PID: %-8d | ", ph.pid()))
                        .append(String.format("命令: %-40s | ",
                                truncate(info.command().orElse("(未知)"), 40)))
                        .append(String.format("启动: %s | ",
                                info.startInstant()
                                        .map(instant -> instant.atZone(ZoneId.systemDefault()).format(formatter))
                                        .orElse("(未知)")))
                        .append(String.format("CPU: %s",
                                info.totalCpuDuration()
                                        .map(d -> String.format("%dms", d.toMillis()))
                                        .orElse("(未知)")))
                        .append("\n");
            }

            if (toIndex < total) {
                sb.append(String.format("\n... 还有 %d 个进程，使用 page=%d 查看下一页", total - toIndex, offset + 1));
            }

            return sb.toString();

        } catch (Exception e) {
            return "获取进程列表失败：" + e.getMessage();
        }
    }

    @Tool(description = "通过 PID 强制终止操作系统中指定的运行进程。需要适当的系统权限。请谨慎使用，这会直接结束目标程序。")
    @Override
    public String killProcess(
            @ToolParam(description = "要终止的进程 ID（PID）") Long pid,
            @ToolParam(description = "是否强制终止（destroyForcibly），默认 true。设为 false 会先尝试正常终止", required = false) Boolean force,
            ToolContext toolContext) {

        if (pid == null || pid <= 0) {
            return "错误：无效的 PID，必须为正整数";
        }

        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isEmpty()) {
            return String.format("错误：PID %d 对应的进程不存在（可能已退出或无权限查看）", pid);
        }

        ProcessHandle handle = handleOpt.get();
        ProcessHandle.Info info = handle.info();
        String command = info.command().orElse("(未知)");
        boolean forceFlag = force != null && force;

        try {
            boolean destroyed;
            String method;
            if (forceFlag) {
                destroyed = handle.destroyForcibly();
                method = "destroyForcibly（强制）";
            } else {
                destroyed = handle.destroy();
                if (!destroyed) {
                    destroyed = handle.destroyForcibly();
                    method = "destroy 失败，已降级为 destroyForcibly（强制）";
                } else {
                    method = "destroy（正常）";
                }
            }

            if (destroyed) {
                return String.format("""
                        【进程已终止】
                        PID: %d
                        命令: %s
                        方式: %s
                        """, pid, command, method);
            } else {
                return String.format("错误：无法终止进程 %d（可能没有权限或进程已退出）", pid);
            }
        } catch (SecurityException e) {
            return String.format("错误：没有权限终止进程 %d - %s", pid, e.getMessage());
        }
    }

    // ==================== PTY Operations (via reflection) ====================

    /**
     * 通过 PTY 启动 REPL 进程（使用反射调用 pty4j）
     */
    private String startPtyProcess(String sessionId, String username, String command,
                                   Path workPath, long waitTimeout) throws IOException {
        try {
            String[] cmdArray = parseCommand(command);

            Class<?> builderClass = Class.forName("com.pty4j.PtyProcessBuilder");
            Object builder = builderClass.getConstructor(String[].class).newInstance((Object) cmdArray);

            builderClass.getMethod("setDirectory", String.class).invoke(builder, workPath.toString());
            builderClass.getMethod("setEnvironment", Map.class).invoke(builder, System.getenv());

            Object ptyObj = builderClass.getMethod("start").invoke(builder);

            // PtyProcess extends Process, so we can cast it
            Process ptyProcess = (Process) ptyObj;
            OutputStream ptyStdin = ptyProcess.getOutputStream();

            // Use reflection to call session.sendSignal for PTY
            ProcessSession session = new ProcessSession(sessionId, username, ptyProcess, ptyStdin,
                    command, workPath.toString(), true, true);

            sessions.put(sessionId, session);
            startOutputDrain(session);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            String initialOutput = session.getFullOutput();

            return String.format("""
                    【PTY 会话已启动】
                    会话ID: %s
                    进程ID: %d
                    命令: %s
                    工作目录: %s
                    PTY模式: 是
                    初始输出:
                    %s

                    提示：PTY 模式支持完整的终端交互。使用 interact_with_process 发送代码，使用 send_signal 发送 Ctrl+C 等信号。
                    """, sessionId, ptyProcess.pid(), command, workPath,
                    initialOutput.isEmpty() ? "(暂无输出，REPL 可能正在加载...)" : initialOutput);

        } catch (Exception e) {
            throw new IOException("PTY 启动失败: " + e.getMessage(), e);
        }
    }

    // ==================== Standard Process Operations ====================

    /**
     * 通过 ProcessBuilder 直接启动解释器（无 shell 包装，保持打开）
     */
    private String startNonShellProcess(String sessionId, String username, String command,
                                        Path workPath, long waitTimeout) throws IOException, InterruptedException {
        String[] cmdArray = parseCommand(command);

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.directory(workPath.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        ProcessSession session = new ProcessSession(sessionId, username, process, process.getOutputStream(),
                command, workPath.toString(), true, false);

        sessions.put(sessionId, session);
        startOutputDrain(session);

        // 等待判断是快速退出还是长会话
        boolean completed = process.waitFor(waitTimeout, TimeUnit.MILLISECONDS);
        if (completed) {
            sessions.remove(sessionId);
            return String.format("""
                    【进程执行完成】
                    命令: %s
                    退出码: %d
                    输出:
                    %s
                    """, command, process.exitValue(), session.getFullOutput());
        }

        String initialOutput = session.getFullOutput();

        return String.format("""
                【会话已启动】（无 PTY）
                会话ID: %s
                进程ID: %d
                命令: %s
                工作目录: %s
                PTY模式: 否
                初始输出:
                %s

                注意：此会话未使用 PTY 启动，不支持 Ctrl+C 等终端信号。交互功能受限，建议安装 pty4j 获得完整体验。
                """, sessionId, process.pid(), command, workPath,
                initialOutput.isEmpty() ? "(暂无输出)" : initialOutput);
    }

    /**
     * 通过 shell 执行一次性命令
     */
    private String startShellProcess(String sessionId, String username, String command,
                                     Path workPath, long waitTimeout) throws IOException, InterruptedException {
        String os = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("/bin/sh", "-c", command);
        }
        pb.directory(workPath.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        ProcessSession session = new ProcessSession(sessionId, username, process, process.getOutputStream(),
                command, workPath.toString(), false, false);

        try {
            boolean completed = process.waitFor(waitTimeout, TimeUnit.MILLISECONDS);
            if (completed) {
                return String.format("""
                        【命令执行完成】
                        命令: %s
                        退出码: %d
                        输出:
                        %s
                        """, command, process.exitValue(), session.getFullOutput());
            } else {
                sessions.put(sessionId, session);
                startOutputDrain(session);
                return String.format("""
                        【命令超时，已转为受管会话】
                        命令: %s
                        会话ID: %s
                        PID: %d
                        超时: %dms
                        当前输出:
                        %s

                        提示：此命令执行时间较长，已转为受管会话。使用 readProcessOutput 读取输出，使用 forceTerminate 终止。
                        """, command, sessionId, process.pid(), waitTimeout,
                        session.getFullOutput());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * 解析命令字符串为数组
     */
    private String[] parseCommand(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }

    /**
     * 启动标准输出 drain 线程
     */
    private void startOutputDrain(ProcessSession session) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(session.getProcess().getInputStream()))) {
                char[] buffer = new char[4096];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    session.appendOutput(new String(buffer, 0, bytesRead));
                }
            } catch (IOException e) {
                // 进程结束或出错时正常退出
            }
        }, "terminal-drain-" + session.getSessionId());
        thread.setDaemon(true);
        thread.start();
        session.setDrainThread(thread);
    }

    /**
     * 强制终止会话
     */
    private String doForceTerminate(ProcessSession session) {
        try {
            Process process = session.getProcess();
            if (process.isAlive()) {
                process.destroyForcibly();
                boolean terminated = process.waitFor(5, TimeUnit.SECONDS);
                if (!terminated) {
                    process.destroyForcibly();
                }
            }

            session.setState(SessionState.TERMINATED);

            Thread drainThread = session.getDrainThread();
            if (drainThread != null && drainThread.isAlive()) {
                drainThread.interrupt();
            }

            String remainingOutput = session.getFullOutput();
            sessions.remove(session.getSessionId());

            return String.format("""
                    【会话已终止】
                    会话ID: %s
                    命令: %s
                    PID: %d
                    持续时间: %d秒
                    最终输出:
                    %s
                    """, session.getSessionId(), session.getCommand(),
                    session.getPid(), session.getDurationSeconds(),
                    remainingOutput.isEmpty() ? "(无额外输出)" : remainingOutput);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "终止会话被中断：" + e.getMessage();
        }
    }

    /**
     * 验证会话存在且属于当前用户
     */
    private ProcessSession validateSession(String sessionId, String username) {
        if (sessionId == null || sessionId.isEmpty()) return null;
        ProcessSession session = sessions.get(sessionId);
        if (session == null) return null;
        if (!username.equals(session.getUsername())) return null;

        if (!session.isAlive() && session.getState() == SessionState.RUNNING) {
            session.setState(SessionState.COMPLETED);
        }
        return session;
    }

    /**
     * 解析工作目录
     */
    private Path resolveWorkingDir(String workingDir, String username) {
        if (workingDir != null && !workingDir.isBlank()) {
            return Paths.get(workingDir).normalize();
        }
        return Paths.get(baseFileDir, username);
    }

    /**
     * 清理已完成的会话
     */
    private void cleanupCompletedSessions() {
        sessions.entrySet().removeIf(entry -> {
            ProcessSession s = entry.getValue();
            return s.getState() == SessionState.COMPLETED || s.getState() == SessionState.TERMINATED;
        });
    }

    /**
     * 会话不存在错误消息
     */
    private String sessionNotFoundMessage(String sessionId) {
        return String.format("错误：会话 %s 不存在。使用 listSessions 查看当前活跃的会话。", sessionId);
    }

    /**
     * 截断字符串
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return "(null)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
