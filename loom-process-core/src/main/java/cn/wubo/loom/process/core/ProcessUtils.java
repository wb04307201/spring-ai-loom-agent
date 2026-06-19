package cn.wubo.loom.process.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台进程管理工具。统一封装：
 * <ul>
 *   <li>子进程启动（含 Windows .bat 临时文件兜底）</li>
 *   <li>stdout/stderr 流消费（守护线程，防管道满阻塞）</li>
 *   <li>超时控制 + 进程树杀死（Windows taskkill /T、Unix ps+kill 递归）</li>
 *   <li>Maven 可执行文件定位</li>
 * </ul>
 */
public final class ProcessUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProcessUtils.class);

    private ProcessUtils() {}

    // ==================== 执行结果 ====================

    /**
     * 进程执行结果。
     */
    public record ExecOutcome(int exitCode, String output, boolean timeout) {
    }

    // ==================== 通用进程执行 ====================

    /**
     * 执行命令并等待完成。合并 stderr 到 stdout。
     *
     * @param cmd      命令行
     * @param workDir  工作目录
     * @param timeoutMs 超时毫秒数
     * @return 执行结果
     */
    public static ExecOutcome runProcess(List<String> cmd, File workDir, long timeoutMs) {
        long start = System.currentTimeMillis();
        Process process = null;
        long pid = -1;
        StringBuilder output = new StringBuilder();
        Thread stdoutThread = null;
        boolean finished;
        boolean timeoutHit;
        int exitCode = -1;
        log.info("runProcess start. cmd={}, workDir={}, timeoutMs={}", cmd, workDir, timeoutMs);
        try {
            process = startProcess(cmd, workDir);
            pid = getProcessPidSafely(process);
            log.info("runProcess started. pid={}", pid);
            stdoutThread = startStreamPump(process.getInputStream(), output, "stdout");
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            timeoutHit = !finished;
            if (timeoutHit) {
                log.warn("process timeout ({}ms), killing tree. cmd={}", timeoutMs, String.join(" ", cmd));
                killProcessTree(process, pid);
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            joinQuietly(stdoutThread, 5000);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                exitCode = -1;
            }
            log.info("runProcess done. exitCode={}, outputBytes={}, elapsed={}ms",
                    exitCode, output.length(), System.currentTimeMillis() - start);
            if (exitCode != 0) {
                log.error("runProcess FULL OUTPUT (exit={}): <<{}>>", exitCode, output);
            }
            return new ExecOutcome(exitCode, output.toString(), timeoutHit);
        } catch (Throwable t) {
            log.error("runProcess failed. cmd={}, err={}", cmd, t.getMessage(), t);
            if (process != null) {
                killProcessTree(process, pid);
            }
            return new ExecOutcome(-1,
                    "启动子进程失败: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                            + "\n--- output ---\n" + output,
                    false);
        }
    }

    /**
     * 执行命令，分离 stdout/stderr。
     *
     * @param cmd      命令行
     * @param workDir  工作目录
     * @param timeoutMs 超时毫秒数
     * @return 执行结果（stdout + stderr 合并）
     */
    public static ExecOutcome runProcessSplitStreams(List<String> cmd, File workDir, long timeoutMs) {
        long start = System.currentTimeMillis();
        Process process = null;
        long pid = -1;
        StringBuilder output = new StringBuilder();
        Thread stdoutThread = null;
        Thread stderrThread = null;
        boolean finished;
        boolean timeoutHit;
        int exitCode = -1;
        log.info("runProcessSplitStreams start. cmd={}, workDir={}, timeoutMs={}", cmd, workDir, timeoutMs);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            process = pb.start();
            pid = getProcessPidSafely(process);
            log.info("process started. pid={}", pid);
            stdoutThread = startStreamPump(process.getInputStream(), output, "stdout");
            stderrThread = startStreamPump(process.getErrorStream(), output, "stderr");
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                finished = false;
            }
            timeoutHit = !finished;
            if (timeoutHit) {
                log.warn("process timeout ({}ms), killing tree.", timeoutMs);
                killProcessTree(process, pid);
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            joinQuietly(stdoutThread, 2000);
            joinQuietly(stderrThread, 2000);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                exitCode = -1;
            }
            log.info("runProcessSplitStreams done. exitCode={}, elapsed={}ms",
                    exitCode, System.currentTimeMillis() - start);
            return new ExecOutcome(exitCode, output.toString(), timeoutHit);
        } catch (Throwable t) {
            log.error("runProcessSplitStreams failed. cmd={}, err={}", cmd, t.getMessage(), t);
            if (process != null) {
                killProcessTree(process, pid);
            }
            return new ExecOutcome(-1,
                    "启动子进程失败: " + t.getClass().getSimpleName() + ": " + t.getMessage()
                            + "\n--- output ---\n" + output,
                    false);
        }
    }

    // ==================== 进程启动 ====================

    /**
     * 启动子进程。Windows 下 cmd.exe /c 命令通过临时 .bat 文件执行，
     * 绕开命令行解析的坑。
     */
    public static Process startProcess(List<String> cmd, File workDir) throws IOException {
        boolean isCmdExe = isWindows() && cmd.size() >= 2
                && cmd.get(0).equalsIgnoreCase("cmd.exe")
                && cmd.get(1).equalsIgnoreCase("/c");
        if (isCmdExe) {
            File bat = createTempBatch(cmd.subList(2, cmd.size()));
            try {
                log.debug("startProcess via temp .bat: {}", bat.getAbsolutePath());
                ProcessBuilder pb = new ProcessBuilder(bat.getAbsolutePath());
                if (workDir != null) pb.directory(workDir);
                pb.redirectErrorStream(true);
                return pb.start();
            } finally {
                bat.deleteOnExit();
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /**
     * 创建临时 .bat 文件。
     */
    public static File createTempBatch(List<String> args) throws IOException {
        File bat = File.createTempFile("loom-run-", ".bat");
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("chcp 65001 >nul\r\n");
        sb.append("call ");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append('"').append(args.get(i).replace("\"", "\\\"")).append('"');
        }
        sb.append("\r\nexit /B %ERRORLEVEL%\r\n");
        Files.writeString(bat.toPath(), sb.toString(), StandardCharsets.UTF_8);
        return bat;
    }

    // ==================== 流处理 ====================

    /**
     * 启动守护线程消费进程输出流，防止管道满阻塞。
     */
    public static Thread startStreamPump(InputStream in, StringBuilder sink, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static void joinQuietly(Thread t, long millis) {
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ==================== 进程 PID ====================

    /**
     * 安全获取进程 PID。
     */
    public static long getProcessPidSafely(Process p) {
        if (p == null) return -1;
        try {
            return p.pid();
        } catch (Throwable t) {
            log.debug("getProcessPid failed, returning -1", t);
            return -1;
        }
    }

    // ==================== 进程树杀死 ====================

    /**
     * 杀掉整个进程树。
     * <ul>
     *   <li>Windows: taskkill /F /T /PID</li>
     *   <li>Unix: destroyForcibly + 递归杀子进程</li>
     * </ul>
     */
    public static void killProcessTree(Process process, long pid) {
        if (process == null) return;
        try {
            if (isWindows()) {
                List<String> cmd = new ArrayList<>();
                cmd.add("taskkill");
                cmd.add("/F");
                cmd.add("/T");
                if (pid > 0) {
                    cmd.add("/PID");
                    cmd.add(String.valueOf(pid));
                } else {
                    process.destroyForcibly();
                    return;
                }
                Process tk = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                String tkOut;
                try (var br = new BufferedReader(
                        new InputStreamReader(tk.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    tkOut = sb.toString();
                }
                boolean tkDone = tk.waitFor(10, TimeUnit.SECONDS);
                if (!tkDone) tk.destroyForcibly();
                log.info("taskkill /F /T /PID {} -> done={}, output={}", pid, tkDone,
                        tkOut.replace('\n', ' ').trim());
            } else {
                process.destroyForcibly();
                killUnixChildrenRecursive(pid);
            }
        } catch (Throwable t) {
            log.warn("killProcessTree failed for pid={}, falling back to destroyForcibly only", pid, t);
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Unix 下递归杀子进程。通过 ps 获取进程树，DFS 遍历并 kill -9。
     */
    public static void killUnixChildrenRecursive(long parentPid) {
        if (parentPid <= 0) return;
        try {
            Process ps = new ProcessBuilder("sh", "-c",
                    "ps -o pid= -o ppid= -A").redirectErrorStream(true).start();
            String out;
            try (var br = new BufferedReader(
                    new InputStreamReader(ps.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                out = sb.toString();
            }
            ps.waitFor(2, TimeUnit.SECONDS);
            Map<Long, List<Long>> children = new HashMap<>();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    long c = Long.parseLong(parts[0]);
                    long pp = Long.parseLong(parts[1]);
                    children.computeIfAbsent(pp, k -> new ArrayList<>()).add(c);
                } catch (NumberFormatException ignored) {
                }
            }
            Deque<Long> stack = new ArrayDeque<>(children.getOrDefault(parentPid, List.of()));
            List<Long> toKill = new ArrayList<>();
            while (!stack.isEmpty()) {
                long c = stack.pop();
                toKill.add(c);
                List<Long> kids = children.get(c);
                if (kids != null) stack.addAll(kids);
            }
            for (long c : toKill) {
                try {
                    new ProcessBuilder("kill", "-9", String.valueOf(c)).start().waitFor(1, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
            }
            if (!toKill.isEmpty()) {
                log.info("Unix process tree killed: parent={}, descendants={}", parentPid, toKill);
            }
        } catch (Throwable t) {
            log.debug("killUnixChildrenRecursive failed (non-fatal)", t);
        }
    }

    // ==================== Maven 定位 ====================

    /**
     * 在 mavenHome/bin/ 下定位 mvn 可执行文件。
     *
     * @param mavenHome Maven 安装目录（可为 null）
     * @return mvn 可执行文件，找不到返回 null
     */
    public static File findMavenExecutable(String mavenHome) {
        File home = mavenHome != null ? new File(mavenHome) : null;
        if (home == null || !home.isDirectory()) return null;
        File bin = new File(home, "bin");
        String[] candidates = isWindows()
                ? new String[]{"mvn.cmd", "mvn.bat", "mvn"}
                : new String[]{"mvn"};
        for (String c : candidates) {
            File exe = new File(bin, c);
            if (exe.isFile()) return exe;
        }
        return null;
    }

    // ==================== 输出处理 ====================

    /**
     * 截断输出到指定行数（保留前 N 行）。
     */
    public static String truncateOutput(String output, int maxLines) {
        if (output == null) return "";
        String[] lines = output.split("\\n", -1);
        if (lines.length <= maxLines) return output;
        String[] truncated = Arrays.copyOfRange(lines, 0, maxLines);
        return String.join("\n", truncated)
                + "\n... (输出已截断，共 " + lines.length + " 行，仅显示前 " + maxLines + " 行)";
    }

    /**
     * 取最后 N 行。
     */
    public static String tail(String s, int n) {
        if (s == null) return "";
        String[] lines = s.split("\\n");
        if (lines.length <= n) return s;
        return String.join("\n", Arrays.copyOfRange(lines, lines.length - n, lines.length));
    }

    // ==================== 平台检测 ====================

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
