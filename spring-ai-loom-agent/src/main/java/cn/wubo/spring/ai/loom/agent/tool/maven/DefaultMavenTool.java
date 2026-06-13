package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import cn.wubo.spring.ai.loom.agent.tool.common.PathSecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Maven 构建工具默认实现。
 * <p>
 * 基于 maven-invoker API，提供编译、打包、测试、依赖分析等功能，
 * 无需依赖系统 shell 或 PATH 上的 {@code mvn} 命令。
 */
public class DefaultMavenTool implements IMavenTool {

    private static final Logger log = LoggerFactory.getLogger(DefaultMavenTool.class);

    private final MavenProperty properties;
    private final String fileBasePath;
    /** 实际使用的 Maven Home（可能是配置值，也可能是自动探测到的）。 */
    private final String resolvedMavenHome;

    public DefaultMavenTool(MavenProperty properties, String fileBasePath) {
        this.properties = properties;
        this.fileBasePath = fileBasePath != null ? fileBasePath : ".local/file";
        // 若用户未配置 mavenHome，尝试自动探测，避免依赖系统 PATH（PATH 中可能存在
        // 损坏的 mvn 包装脚本——如 npm 全局安装的 mvn——导致 maven-invoker 抛
        // "Error configuring command line"）
        this.resolvedMavenHome = MavenHomeResolver.resolve(properties.getMavenHome());
        log.info("MavenTool initialized: mavenHome={}, localRepository={}, fileBasePath={}",
                resolvedMavenHome, properties.getLocalRepository(), this.fileBasePath);
    }

    // ==================== Tool Methods ====================

    // ==================== Tool Methods ====================

    @Tool(description = "执行任意 Maven 命令（通用入口）。支持指定 goals（如 clean、compile、package、install、deploy）、工作目录、POM 路径、属性和超时。")
    @Override
    public String mavenExecute(
            @ToolParam(description = "要执行的 Maven 目标列表，如 [\"clean\", \"package\"] 或 [\"dependency:tree\"]") List<String> goals,
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "Maven 属性（-D 参数），键值对 Map", required = false) Map<String, String> properties,
            @ToolParam(description = "超时时间（毫秒），默认 300000ms（5 分钟）", required = false) Long timeoutMs,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";
        if (goals == null || goals.isEmpty()) return "错误：goals 不能为空";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在用户目录 " + workDir + " 中未找到 pom.xml，请指定正确的 pomPath 或 workingDir";

        return executeMaven(goals, workDir, pomFile, properties, timeoutMs);
    }

    @Tool(description = "编译 Java 项目（mvn compile）。自动查找 pom.xml，支持跳过测试和设置属性。")
    @Override
    public String mavenBuild(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "Maven 属性（-D 参数），如 {\"skipTests\": \"true\"}", required = false) Map<String, String> properties,
            @ToolParam(description = "是否跳过测试，默认 false", required = false) Boolean skipTests,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("compile");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        if (skipTests != null && skipTests) {
            props.put("skipTests", "true");
        }
        return executeMaven(goals, workDir, pomFile, props, null);
    }

    @Tool(description = "打包项目（mvn package）。生成 JAR/WAR 等构件，支持跳过测试、设置属性和超时。")
    @Override
    public String mavenPackage(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "Maven 属性（-D 参数）", required = false) Map<String, String> properties,
            @ToolParam(description = "是否跳过测试，默认 true", required = false) Boolean skipTests,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("package");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        boolean skip = skipTests == null || skipTests;
        if (skip) {
            props.put("skipTests", "true");
        }
        return executeMaven(goals, workDir, pomFile, props, null);
    }

    @Tool(description = "运行单元测试（mvn test）。支持测试模式匹配（如 *ServiceTest）和自定义属性。")
    @Override
    public String mavenTest(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "测试类名模式匹配，如 *ServiceTest（对应 -Dtest 参数）", required = false) String testPattern,
            @ToolParam(description = "Maven 属性（-D 参数）", required = false) Map<String, String> properties,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("test");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        if (testPattern != null && !testPattern.isBlank()) {
            props.put("test", testPattern);
        }
        return executeMaven(goals, workDir, pomFile, props, null);
    }

    @Tool(description = "查看项目依赖树（mvn dependency:tree）。支持按范围过滤（compile、runtime、test、provided）。")
    @Override
    public String mavenDependencyTree(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            @ToolParam(description = "包含的依赖范围：compile、runtime、test、provided（可选，默认全部）", required = false) String includeScope,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("dependency:tree");
        Map<String, String> props = new LinkedHashMap<>();
        if (includeScope != null && !includeScope.isBlank()) {
            props.put("includesScope", includeScope);
        }
        return executeMaven(goals, workDir, pomFile, props, null);
    }

    @Tool(description = "验证项目结构是否正确（mvn validate）。检查 pom.xml 和必要资源是否存在。")
    @Override
    public String mavenValidate(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = resolveWorkingDir(workingDir, username);
        File pomFile = resolvePomFile(pomPath, username, workDir);
        if (pomFile == null) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        return executeMaven(List.of("validate"), workDir, pomFile, Map.of(), null);
    }

    // ==================== Internal Helpers ====================

    /**
     * 核心 Maven 执行方法
     * <p>
     * 直接使用 {@link ProcessBuilder} 执行 mvn 子进程，避开 maven-invoker 3.3.0 /
     * plexus-utils 3.3.0 的两个关键缺陷：
     * <ol>
     *   <li>异常路径（超时 / interrupt）下不会清理注册的 shutdown hook，导致
     *       {@link Process} 对象被 hook 持续引用、其内部命名管道句柄在 JVM
     *       退出前永不释放。Windows 上这些未关闭的句柄会锁住
     *       {@code target/} 与 {@code .m2/repository/} 下的文件，使目录删除失败。</li>
     *   <li>封装在 {@link Invoker} 后，我们无法拿到子进程 {@link Process} 引用，
     *       只能在调用方用 {@link Future#cancel(boolean)} 发 {@code Thread.interrupt()}，
     *       但 {@link Process#waitFor()} 不响应 interrupt —— 结果是 mvn 子进程
     *       在工具调用被取消 / 超时后继续运行，继续往 {@code target/} 写 class 文件、
     *       持续持有 jar 的 mmap 句柄。</li>
     * </ol>
     * 改用 {@link ProcessBuilder} 后：
     * <ul>
     *   <li>不注册任何 JVM shutdown hook</li>
     *   <li>使用 {@link Process#waitFor(long, TimeUnit)} 做干净超时</li>
     *   <li>超时后用 {@link Process#destroyForcibly()} 杀进程，再用
     *       {@code destroyForcibly + waitFor} 兜底，确保 mvn 在 Windows 上不再
     *       持有任何 mmap 句柄</li>
     *   <li>显式关闭 stdout / stderr / stdin 流，让 GC 不必等 hook 释放</li>
     * </ul>
     */
    private String executeMaven(List<String> goals, File workDir, File pomFile,
                                Map<String, String> props, Long timeoutMs) {

        // ===== 顶层 try-catch：任何异常都不能逃出工具方法 =====
        // 历史上层 try-catch 漏掉了 stream pump 里的 IllegalStateException、
        // process.exitValue() 在某些 Windows 状态下的 IllegalThreadStateException、
        // 极端情况下 Process 句柄已被 native 释放时的 IllegalArgumentException。
        // 任何一个逃出去都可能导致 Spring AI 工具回调整条链断掉、聊天会话卡死。
        // 现在统一兜底成 formatError 返回，工具调用始终有结果。
        long startTime = System.currentTimeMillis();
        try {
            return executeMavenInternal(goals, workDir, pomFile, props, timeoutMs, startTime);
        } catch (Throwable t) {
            log.error("Maven tool unexpected failure. workDir={}, goals={}", workDir, goals, t);
            return formatError(goals, workDir, pomFile, startTime,
                    "工具内部异常: " + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    "完整堆栈已写入日志；如反复出现请附 stacktrace 反馈");
        }
    }

    private String executeMavenInternal(List<String> goals, File workDir, File pomFile,
                                        Map<String, String> props, Long timeoutMs, long startTime) {
        String goalStr = String.join(" ", goals);
        long timeout = timeoutMs != null && timeoutMs > 0
                ? timeoutMs
                : properties.getDefaultTimeoutMs();
        log.info("Maven execute start. workDir={}, goals=[{}], timeoutMs={}",
                workDir, goalStr, timeout);

        File mvnExe = findMavenExecutable();
        if (mvnExe == null) {
            log.warn("Maven executable not found. resolvedMavenHome={}", resolvedMavenHome);
            return formatError(goals, workDir, pomFile, startTime,
                    "找不到 mvn 可执行文件（resolvedMavenHome="
                            + resolvedMavenHome + "）",
                    "提示：检查 spring.ai.loom.agent.maven.mavenHome 是否指向真实 Maven 安装目录");
        }

        // 1. 构造命令行
        List<String> cmd = new ArrayList<>();
        cmd.add(mvnExe.getAbsolutePath());
        cmd.add("-B"); // batch mode (no interactive prompts)
        cmd.add("-e"); // show errors
        cmd.add("-f");
        cmd.add(pomFile.getAbsolutePath());
        if (props != null) {
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                cmd.add("-D");
                cmd.add(e.getKey() + "=" + e.getValue());
            }
        }
        if (properties.getLocalRepository() != null && !properties.getLocalRepository().isBlank()) {
            cmd.add("-D");
            cmd.add("maven.repo.local=" + new File(properties.getLocalRepository()).getAbsolutePath());
        }
        cmd.addAll(goals);

        // 2. 启动子进程
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir);
            // 不合并 stderr —— 用户希望分别看到错误输出
            process = pb.start();
        } catch (IOException e) {
            log.warn("Maven process start failed. workDir={}, error={}", workDir, e.getMessage());
            return formatError(goals, workDir, pomFile, startTime,
                    "启动 mvn 失败: " + e.getMessage(),
                    buildMavenNotFoundHint(e));
        }

        long pid = getProcessPidSafely(process);
        log.info("Maven process started. pid={}, mvnExe={}", pid, mvnExe.getAbsolutePath());

        // 3. 启动两个守护线程消费 stdout / stderr（必须消费，否则 mvn 写满管道后会阻塞）
        StringBuilder output = new StringBuilder();
        Thread stdoutThread = startStreamPump(process.getInputStream(), output, "mvn-stdout");
        Thread stderrThread = startStreamPump(process.getErrorStream(), output, "mvn-stderr");

        // 4. 等待子进程完成（带超时）
        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }

        // 5. 超时：杀整棵进程树，释放所有 mmap 句柄（Windows 关键）
        //
        // 历史上用 process.destroyForcibly() 只能杀第一层 cmd.exe，
        // 真正的 mvn java.exe（孙进程）变成孤儿继续跑、继续下载、继续
        // mmap .m2/repository/*.jar，把后续 LLM 操作拖死。
        // 现在改用 taskkill /F /T 一次把整棵树连根拔起。
        boolean timeoutHit = !finished;
        if (timeoutHit) {
            log.warn("Maven execution exceeded {}ms, killing process tree. workDir={}, pid={}",
                    timeout, workDir, pid);
            killProcessTree(process, pid);
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 6. 等消费线程结束（防止丢尾部输出）
        joinQuietly(stdoutThread, 2000);
        joinQuietly(stderrThread, 2000);

        // 7. 显式关闭流（让 Process 内部命名管道句柄立即释放 —— Windows 文件锁的真正解法）
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());

        // 8. 格式化结果
        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }
        String errorMsg;
        if (timeoutHit) {
            errorMsg = "超时（" + timeout + "ms），已强制终止 mvn 进程树（含孙进程）";
        } else if (exitCode != 0) {
            errorMsg = "Maven 返回非零退出码: " + exitCode;
        } else {
            errorMsg = null;
        }
        String result = formatResult(goals, workDir, pomFile, startTime,
                exitCode, errorMsg, truncateOutput(output.toString()), timeoutHit);
        log.info("Maven execute done. workDir={}, exitCode={}, timeout={}, costMs={}",
                workDir, exitCode, timeoutHit, System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 启动子进程后立刻在守护线程里消费其 stdout / stderr，
     * 否则 mvn 在大量输出时会把管道写满、阻塞子进程。
     * 返回的线程会在流关闭后自然结束。
     */
    private static Thread startStreamPump(java.io.InputStream in, StringBuilder sink, String name) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (java.io.IOException ignored) {
                // 进程被 destroyForcibly 时流会抛 IOException，正常
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t, long millis) {
        if (t == null) return;
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (java.io.IOException ignored) {
        }
    }

    /**
     * 安全地获取子进程 PID（仅用于日志和 taskkill 定位）。
     * <p>
     * {@link Process#pid()} 在 Java 9+ 才有；本项目 JDK 17 一定存在，但出于
     * 防御性仍包一层 try-catch：万一是自定义 Process 子类或者句柄已被回收，
     * 不应让这个日志查询把整条工具链拖死。
     */
    private static long getProcessPidSafely(Process p) {
        if (p == null) return -1;
        try {
            return p.pid();
        } catch (Throwable t) {
            log.debug("getProcessPid failed, returning -1", t);
            return -1;
        }
    }

    /**
     * 杀掉整个 mvn 进程树，而不仅仅是 {@link Process#destroyForcibly()} 能
     * 触及的第一层子进程。
     * <p>
     * <b>为什么需要这个？</b>在 Windows 上，{@code Process.destroyForcibly()}
     * 只会 {@code TerminateProcess} 当前 {@link Process} 句柄对应的进程
     * （mvn.cmd 的 cmd.exe）。但 mvn.cmd 会自己启动一个
     * {@code java.exe org.apache.maven.cli.MavenCli ...} 作为子进程；
     * TerminateProcess 不会冒泡到孙进程，mvn java.exe 会变成孤儿继续跑：
     * <ul>
     *   <li>继续下载依赖 → 占网络</li>
     *   <li>继续写 target/ → 锁住 class 文件</li>
     *   <li>继续 mmap .m2/repository/*.jar → 锁住 jar</li>
     *   <li>继续吃 CPU/内存</li>
     * </ul>
     * 孤儿进程就是用户报告"服务自己就停了" / "目录被锁住" 的根因。
     * <p>
     * <b>策略：</b>
     * <ul>
     *   <li>Windows：用 {@code taskkill /F /T /PID &lt;pid&gt;} 一次杀整棵树</li>
     *   <li>Linux/macOS：先 {@code destroyForcibly} 杀自己，再读 {@code /proc}
     *       找子进程递归杀（POSIX 上没有跨进程的 Job Object 兜底）</li>
     * </ul>
     * <b>不会影响父 JVM：</b>taskkill 只针对传入的 pid 及其后代，不会沿父链上溯；
     * destroyForcibly 也只杀 Process 自己的句柄。这正是用户怀疑、但实际
     * 不会发生的事。
     */
    private void killProcessTree(Process process, long pid) {
        if (process == null) return;
        try {
            if (isWindows()) {
                // taskkill 是 Windows 内建命令，/F = 强制，/T = 连同子进程一起杀
                List<String> cmd = new ArrayList<>();
                cmd.add("taskkill");
                cmd.add("/F");
                cmd.add("/T");
                if (pid > 0) {
                    cmd.add("/PID");
                    cmd.add(String.valueOf(pid));
                } else {
                    // 拿不到 pid 时退回到 destroyForcibly 单进程兜底
                    process.destroyForcibly();
                    return;
                }
                Process tk = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .start();
                String tkOut;
                try (var br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(tk.getInputStream(),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    tkOut = sb.toString();
                }
                boolean tkDone = tk.waitFor(10, TimeUnit.SECONDS);
                if (!tkDone) tk.destroyForcibly();
                log.info("taskkill /F /T /PID {} → done={}, output={}", pid, tkDone,
                        tkOut.replace('\n', ' ').trim());
            } else {
                // Unix: 先杀自己，再尝试递归杀 /proc 下看到的子进程
                process.destroyForcibly();
                killUnixChildrenRecursive(pid);
            }
        } catch (Throwable t) {
            // 杀进程这一步即使失败，也不应让整个工具调用崩掉
            log.warn("killProcessTree failed for pid={}, falling back to destroyForcibly only",
                    pid, t);
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 在 Linux/macOS 上递归杀子进程。读 /proc（Linux）或 ps -o ppid（macOS）。
     * 失败时静默退避 —— Windows 是用户的主战场，Unix 路径以"尽力而为"为目标。
     */
    private void killUnixChildrenRecursive(long parentPid) {
        if (parentPid <= 0) return;
        try {
            Process ps = new ProcessBuilder("sh", "-c",
                    "ps -o pid= -o ppid= -A").redirectErrorStream(true).start();
            String out;
            try (var br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(ps.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                out = sb.toString();
            }
            ps.waitFor(2, TimeUnit.SECONDS);
            java.util.Map<Long, java.util.List<Long>> children = new java.util.HashMap<>();
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 2) continue;
                try {
                    long c = Long.parseLong(parts[0]);
                    long pp = Long.parseLong(parts[1]);
                    children.computeIfAbsent(pp, k -> new java.util.ArrayList<>()).add(c);
                } catch (NumberFormatException ignored) {
                }
            }
            java.util.Deque<Long> stack = new java.util.ArrayDeque<>(children.getOrDefault(parentPid, java.util.List.of()));
            java.util.List<Long> toKill = new java.util.ArrayList<>();
            while (!stack.isEmpty()) {
                long c = stack.pop();
                toKill.add(c);
                java.util.List<Long> kids = children.get(c);
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

    /**
     * 定位 mvn 可执行文件。优先取用户配置的 mavenHome，否则取自动探测结果。
     */
    private File findMavenExecutable() {
        File home = resolvedMavenHome != null ? new File(resolvedMavenHome) : null;
        if (home == null || !home.isDirectory()) return null;
        File bin = new File(home, "bin");
        // Windows 优先 mvn.cmd / mvn.bat；Unix 取 mvn
        String[] candidates = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? new String[]{"mvn.cmd", "mvn.bat", "mvn"}
                : new String[]{"mvn"};
        for (String c : candidates) {
            File exe = new File(bin, c);
            if (exe.isFile()) return exe;
        }
        return null;
    }

    private String formatError(List<String> goals, File workDir, File pomFile,
                               long startTime, String errorMsg, String hint) {
        return String.format("""
                ❌ Maven 执行异常
                命令: mvn %s
                工作目录: %s
                POM: %s
                耗时: %dms
                异常: %s
                %s
                """, String.join(" ", goals), workDir, pomFile,
                System.currentTimeMillis() - startTime, errorMsg,
                hint == null ? "" : hint);
    }

    /**
     * 截断输出到最大行数
     */
    private String truncateOutput(String output) {
        if (output == null) return "";
        int maxLines = properties.getMaxOutputLines();
        String[] lines = output.split("\\n", -1);
        if (lines.length <= maxLines) return output;

        String[] truncated = Arrays.copyOfRange(lines, 0, maxLines);
        return String.join("\n", truncated)
                + "\n... (输出已截断，共 " + lines.length + " 行，仅显示前 " + maxLines + " 行)";
    }

    /**
     * 格式化执行结果
     */
    private String formatResult(List<String> goals, File workDir, File pomFile,
                                long startTime, int exitCode, String errorMsg,
                                String output, boolean timeout) {

        long duration = System.currentTimeMillis() - startTime;
        String goalStr = String.join(" ", goals);

        String statusLine = (exitCode == 0 && !timeout) ? "✅ 成功" : "❌ 失败";
        if (timeout) statusLine = "⏱ 超时";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s Maven 执行%n", statusLine));
        sb.append(String.format("命令: mvn %s%n", goalStr));
        sb.append(String.format("工作目录: %s%n", workDir));
        sb.append(String.format("POM: %s%n", pomFile));
        sb.append(String.format("耗时: %dms%n", duration));
        if (errorMsg != null) {
            sb.append(String.format("错误: %s%n", errorMsg));
        }
        sb.append(String.format("退出码: %d%n", exitCode));
        sb.append("\n--- 输出 ---\n");
        sb.append(output.isEmpty() ? "(无输出)" : output);
        return sb.toString();
    }

    /**
     * 获取用户名
     */
    private String getUsername(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object u = toolContext.getContext().get("username");
        if (u == null || u.toString().isBlank()) return null;
        return u.toString();
    }

    /**
     * 解析工作目录。限定在用户文件目录 {fileBasePath}/{username}/ 内，防止越权操作。
     */
    private File resolveWorkingDir(String workingDir, String username) {
        if (workingDir != null && !workingDir.isBlank()) {
            return validatePathInUserDir(username, workingDir, "workingDir");
        }
        // 默认使用用户文件目录
        return getUserFileDir(username).toFile();
    }

    /**
     * 解析 POM 文件。限定在用户文件目录内。
     * <p>
     * 当 pomPath 为相对路径时，优先相对于 workDir 解析；
     * 当 workDir 不存在时，相对于用户文件目录解析。
     */
    private File resolvePomFile(String pomPath, String username, File workDir) {
        if (pomPath != null && !pomPath.isBlank()) {
            Path userDir = getUserFileDir(username);
            Path inputPath = Paths.get(pomPath);
            Path resolved;

            if (inputPath.isAbsolute()) {
                resolved = inputPath.toAbsolutePath().normalize();
            } else if (workDir != null) {
                resolved = workDir.toPath().toAbsolutePath().resolve(pomPath).normalize();
            } else {
                resolved = userDir.resolve(pomPath).toAbsolutePath().normalize();
            }

            Path baseNorm = userDir.toAbsolutePath().normalize();
            if (!resolved.startsWith(baseNorm)) {
                return null;
            }
            // symlink 防御：路径或祖先有软链指向 userDir 外时直接拒绝
            try {
                PathSecurityUtils.assertInsideUserDir(resolved, userDir, true);
            } catch (IOException | SecurityException e) {
                log.warn("POM 路径 symlink 校验失败：{} - {}", resolved, e.getMessage());
                return null;
            }
            return resolved.toFile();
        }
        File f = workDir != null ? new File(workDir, "pom.xml")
                : new File(getUserFileDir(username).toFile(), "pom.xml");
        return f.exists() ? f : null;
    }

    /**
     * 获取用户文件目录：{fileBasePath}/{username}/
     */
    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    /**
     * 校验路径是否在用户文件目录内，防止目录穿越 + symlink 越界。
     * <p>
     * 支持相对路径（相对于用户文件目录）和绝对路径，
     * 但规范化后必须以用户文件目录为前缀，且不能通过 symlink 越界。
     * <p>
     * 越权时返回 null（不抛异常），与"文件不存在"语义一致 —— 调用方
     * 已经按 null = "未找到" 处理。
     */
    private File validatePathInUserDir(String username, String path, String paramName) {
        if (username == null || username.isBlank()) {
            return null;
        }
        Path userDir = getUserFileDir(username);
        Path inputPath = Paths.get(path);
        Path resolved;

        if (inputPath.isAbsolute()) {
            resolved = inputPath.toAbsolutePath().normalize();
        } else {
            resolved = userDir.resolve(path).toAbsolutePath().normalize();
        }

        Path baseNorm = userDir.toAbsolutePath().normalize();
        if (!resolved.startsWith(baseNorm)) {
            log.warn("{} 超出用户文件目录：{} (userDir={})", paramName, resolved, userDir);
            return null;
        }
        // symlink 防御
        try {
            PathSecurityUtils.assertInsideUserDir(resolved, userDir, true);
        } catch (IOException | SecurityException e) {
            log.warn("{} symlink 校验失败：{} - {}", paramName, resolved, e.getMessage());
            return null;
        }
        return resolved.toFile();
    }

    // ==================== Maven Home 解析 ====================
    // Maven home 解析统一委托给 MavenHomeResolver，见同包下共享工具类。

    /**
     * 在 Maven 找不到时构造诊断提示。判断标准：异常消息含
     * {@code "Error configuring command line"}（maven-invoker 找不到 mvn 时的典型错误）。
     */
    private String buildMavenNotFoundHint(Throwable e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (!msg.contains("Error configuring command line")) {
            return "";
        }
        String envM2 = System.getenv("MAVEN_HOME");
        String envM1 = System.getenv("M2_HOME");
        return String.format("""
                提示：maven-invoker 找不到可用的 Maven 可执行文件。可能原因：
                  1) 系统 PATH 上的 mvn/mvn.cmd 损坏或不是真正的 Maven
                  2) 未设置 MAVEN_HOME / M2_HOME 环境变量
                  3) 未在 application.yml 中配置 spring.ai.loom.agent.maven.mavenHome
                当前状态：
                  - 配置的 mavenHome    : %s
                  - resolvedMavenHome   : %s
                  - MAVEN_HOME 环境变量  : %s
                  - M2_HOME 环境变量     : %s
                解决方法（二选一）：
                  A) 在 application.yml 中显式配置：
                       spring.ai.loom.agent.maven.mavenHome: C:\\developer\\apache-maven-3.9.16
                  B) 设置环境变量 MAVEN_HOME 指向真实的 Maven 安装目录
                """,
                properties.getMavenHome(),
                resolvedMavenHome,
                envM2 == null ? "<未设置>" : envM2,
                envM1 == null ? "<未设置>" : envM1);
    }
}
