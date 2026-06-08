package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import org.apache.maven.shared.invoker.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

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
        this.resolvedMavenHome = resolveMavenHome(properties.getMavenHome());
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
     */
    private String executeMaven(List<String> goals, File workDir, File pomFile,
                                Map<String, String> props, Long timeoutMs) {

        String goalStr = String.join(" ", goals);
        long startTime = System.currentTimeMillis();

        try {
            InvocationRequest request = new DefaultInvocationRequest();
            request.setGoals(goals);
            request.setPomFile(pomFile);
            request.setBaseDirectory(workDir);
            request.setBatchMode(true);
            request.setOffline(false);
            request.setShowErrors(true);

            // Properties
            if (props != null && !props.isEmpty()) {
                Properties mavenProps = new Properties();
                mavenProps.putAll(props);
                request.setProperties(mavenProps);
            }

            // Output capture
            StringWriter outputWriter = new StringWriter();
            PrintWriter outputPrinter = new PrintWriter(outputWriter);
            InvocationOutputHandler outputHandler = line -> outputPrinter.println(line);
            request.setOutputHandler(outputHandler);

            // Invoker setup
            Invoker invoker = new DefaultInvoker();
            if (resolvedMavenHome != null) {
                invoker.setMavenHome(new File(resolvedMavenHome));
            }
            if (properties.getLocalRepository() != null && !properties.getLocalRepository().isBlank()) {
                invoker.setLocalRepositoryDirectory(new File(properties.getLocalRepository()));
            }

            // Execute with timeout
            long timeout = timeoutMs != null && timeoutMs > 0
                    ? timeoutMs
                    : properties.getDefaultTimeoutMs();

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<InvocationResult> future = executor.submit(() -> invoker.execute(request));
            executor.shutdown();

            InvocationResult result;
            try {
                result = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                executor.shutdownNow();
                return formatResult(goals, workDir, pomFile, startTime,
                        1, "超时（" + timeout + "ms）",
                        truncateOutput(outputWriter.toString()), true);
            }
            int exitCode = result.getExitCode();
            String errorMsg = result.getExecutionException() != null
                    ? result.getExecutionException().getMessage()
                    : (exitCode != 0 ? "Maven 返回非零退出码: " + exitCode : null);

            outputPrinter.flush();
            String output = outputWriter.toString();

            return formatResult(goals, workDir, pomFile, startTime,
                    exitCode, errorMsg, truncateOutput(output), false);

        } catch (Exception e) {
            String hint = buildMavenNotFoundHint(e);
            return String.format("""
                    ❌ Maven 执行异常
                    命令: mvn %s
                    工作目录: %s
                    POM: %s
                    耗时: %dms
                    异常: %s
                    %s
                    """, goalStr, workDir, pomFile,
                    System.currentTimeMillis() - startTime,
                    e.getMessage(), hint);
        }
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
        return (String) toolContext.getContext().get("username");
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
                resolved = inputPath.normalize();
            } else if (workDir != null) {
                resolved = workDir.toPath().resolve(pomPath).normalize();
            } else {
                resolved = userDir.resolve(pomPath).normalize();
            }

            if (!resolved.startsWith(userDir)) {
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
     * 校验路径是否在用户文件目录内，防止目录穿越。
     * <p>
     * 支持相对路径（相对于用户文件目录）和绝对路径，
     * 但规范化后必须以用户文件目录为前缀。
     */
    private File validatePathInUserDir(String username, String path, String paramName) {
        Path userDir = getUserFileDir(username);
        Path inputPath = Paths.get(path);
        Path resolved;

        if (inputPath.isAbsolute()) {
            resolved = inputPath.normalize();
        } else {
            resolved = userDir.resolve(path).normalize();
        }

        if (!resolved.startsWith(userDir)) {
            return null;
        }
        return resolved.toFile();
    }

    // ==================== Maven Home 解析 ====================

    /**
     * 解析实际使用的 Maven Home。
     * <p>
     * 优先级：
     * <ol>
     *   <li>用户通过 {@code spring.ai.loom.agent.maven.mavenHome} 配置的值（若合法）</li>
     *   <li>环境变量 {@code MAVEN_HOME} / {@code M2_HOME}（若合法）</li>
     *   <li>Windows 常见安装路径（{@code C:\developer\apache-maven-*},
     *       {@code C:\Program Files\Apache Maven}, {@code C:\apache-maven-*} 等）</li>
     * </ol>
     * 目的：避免依赖系统 {@code PATH} 上的 {@code mvn} 命令——
     * 部分 Windows 环境下 {@code PATH} 中的 {@code mvn} 可能是 npm 包装脚本、
     * 已损坏的旧版本目录等，会让 {@code maven-invoker} 抛
     * "Error configuring command line"。
     */
    static String resolveMavenHome(String configured) {
        if (isValidMavenHome(configured)) {
            return new File(configured).getAbsolutePath();
        }
        String env = System.getenv("MAVEN_HOME");
        if (isValidMavenHome(env)) {
            return new File(env).getAbsolutePath();
        }
        env = System.getenv("M2_HOME");
        if (isValidMavenHome(env)) {
            return new File(env).getAbsolutePath();
        }
        File auto = autoDetectMavenHome();
        if (auto != null) {
            return auto.getAbsolutePath();
        }
        return null;
    }

    /**
     * 校验给定路径是否是一个合法的 Maven 安装目录（含 {@code bin/mvn} 或 {@code bin/mvn.cmd}）。
     */
    private static boolean isValidMavenHome(String path) {
        if (path == null || path.isBlank()) return false;
        File home = new File(path);
        if (!home.isDirectory()) return false;
        return new File(home, "bin/mvn").isFile()
                || new File(home, "bin/mvn.cmd").isFile()
                || new File(home, "bin/mvn.bat").isFile();
    }

    /**
     * 在 Windows 常见路径下扫描 Maven 安装目录。
     */
    private static File autoDetectMavenHome() {
        // 注意：默认不依赖 PATH，避免被坏掉的 mvn 包装脚本干扰
        String[] roots = {
                "C:\\developer",
                "C:\\Program Files",
                "C:\\Program Files (x86)",
                "C:\\",
                "C:\\Tools",
                System.getProperty("user.home") + "\\.m2\\wrapper\\dists"
        };
        for (String root : roots) {
            File dir = new File(root);
            if (!dir.isDirectory()) continue;
            File[] children = dir.listFiles((d, name) -> {
                String n = name.toLowerCase();
                return n.startsWith("apache-maven-") || n.equalsIgnoreCase("Apache Maven")
                        || n.equalsIgnoreCase("maven");
            });
            if (children == null) continue;
            // 排序后取最新（按目录名降序，版本号靠后的排前面）
            Arrays.sort(children, Comparator.comparing(File::getName).reversed());
            for (File child : children) {
                if (isValidMavenHome(child.getAbsolutePath())) {
                    log.info("Auto-detected Maven Home: {}", child.getAbsolutePath());
                    return child;
                }
            }
        }
        return null;
    }

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
