package cn.wubo.spring.ai.loom.agent.tool.terminal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 终端会话数据类，封装单个受管终端会话的状态。
 * <p>
 * 支持两种进程类型：标准 Process 和 PtyProcess（伪终端）。
 * 由于 pty4j 是可选依赖，所有 PTY 相关操作通过反射完成，
 * 因此本类统一使用 Process 类型（PtyProcess 继承自 Process）。
 */
public class ProcessSession {

    private static final Logger log = LoggerFactory.getLogger(ProcessSession.class);
    private static final int MAX_OUTPUT_BUFFER_SIZE = 50 * 1024;

    private final String sessionId;
    private final String username;
    private final Process process;
    private final OutputStream stdin;
    private final String command;
    private final String workingDir;
    private final StringBuilder outputBuffer;
    private final LocalDateTime startTime;
    private final boolean isRepl;
    private final boolean isPty;
    private final AtomicInteger lastReadIndex;
    private volatile SessionState state;
    private volatile boolean isBlocked;
    private Thread drainThread;

    /**
     * 标准 Process 构造函数
     */
    public ProcessSession(String sessionId, String username, Process process, OutputStream stdin,
                          String command, String workingDir, boolean isRepl, boolean isPty) {
        this.sessionId = sessionId;
        this.username = username;
        this.process = process;
        this.stdin = stdin;
        this.command = command;
        this.workingDir = workingDir;
        this.outputBuffer = new StringBuilder();
        this.startTime = LocalDateTime.now();
        this.isRepl = isRepl;
        this.isPty = isPty;
        this.lastReadIndex = new AtomicInteger(0);
        this.state = SessionState.RUNNING;
        this.isBlocked = false;
    }

    /**
     * 获取底层进程对象
     */
    public Process getProcess() {
        return process;
    }

    /**
     * 获取进程 PID
     */
    public long getPid() {
        return process.pid();
    }

    /**
     * 判断进程是否存活
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * 获取退出码（仅进程结束后有效）
     */
    public int exitValue() {
        return process.exitValue();
    }

    /**
     * 获取标准输入流，用于发送命令
     */
    public OutputStream getStdin() {
        return stdin;
    }

    /**
     * 向进程发送控制信号（如 Ctrl+C）
     * PTY 模式下通过反射写入对应控制字符，标准进程模式返回 false
     */
    public boolean sendSignal(String signal) {
        if (isPty && process.isAlive()) {
            byte[] bytes = switch (signal.toLowerCase()) {
                case "interrupt", "ctrl-c", "sigint" -> new byte[]{0x03};
                case "eof", "ctrl-d", "eot" -> new byte[]{0x04};
                case "quit", "ctrl-\\", "sigquit" -> new byte[]{0x1C};
                default -> null;
            };
            if (bytes != null) {
                try {
                    stdin.write(bytes);
                    stdin.flush();
                    return true;
                } catch (Exception e) {
                    log.debug("Failed to send signal: {}", e.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * 追加输出到缓冲区，自动裁剪
     */
    public synchronized void appendOutput(String text) {
        outputBuffer.append(text);
        if (outputBuffer.length() > MAX_OUTPUT_BUFFER_SIZE) {
            int trimLength = outputBuffer.length() - MAX_OUTPUT_BUFFER_SIZE;
            outputBuffer.delete(0, trimLength);
            int lastIndex = lastReadIndex.get();
            if (lastIndex < trimLength) {
                lastReadIndex.set(0);
            } else {
                lastReadIndex.addAndGet(-trimLength);
            }
        }
    }

    /**
     * 获取从指定位置开始的输出
     */
    public synchronized String getOutputFrom(int fromIndex) {
        if (fromIndex >= outputBuffer.length()) {
            return "";
        }
        return outputBuffer.substring(fromIndex);
    }

    /**
     * 获取当前输出缓冲区总长度
     */
    public synchronized int getOutputLength() {
        return outputBuffer.length();
    }

    /**
     * 获取最后 N 行输出
     */
    public synchronized String getTailOutput(int lines) {
        String content = outputBuffer.toString();
        if (content.isEmpty()) {
            return "";
        }
        String[] allLines = content.split("\n");
        int start = Math.max(0, allLines.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(allLines, start, allLines.length));
    }

    /**
     * 获取完整输出（可能已裁剪）
     */
    public synchronized String getFullOutput() {
        return outputBuffer.toString();
    }

    // ==================== Getters ====================

    public String getSessionId() { return sessionId; }
    public String getUsername() { return username; }
    public String getCommand() { return command; }
    public String getWorkingDir() { return workingDir; }
    public LocalDateTime getStartTime() { return startTime; }
    public boolean isRepl() { return isRepl; }
    public boolean isPty() { return isPty; }
    public AtomicInteger getLastReadIndex() { return lastReadIndex; }
    public SessionState getState() { return state; }
    public void setState(SessionState state) { this.state = state; }
    public boolean isBlocked() { return isBlocked; }
    public void setBlocked(boolean blocked) { this.isBlocked = blocked; }
    public Thread getDrainThread() { return drainThread; }
    public void setDrainThread(Thread drainThread) { this.drainThread = drainThread; }

    /**
     * 获取会话持续时间（秒）
     */
    public long getDurationSeconds() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
    }
}
