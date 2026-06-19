package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.loom.maven.core.MavenOperations;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maven 构建工具默认实现。
 * <p>
 * 基于 maven-invoker API，提供编译、打包、测试、依赖分析等功能，
 * 无需依赖系统 shell 或 PATH 上的 {@code mvn} 命令。
 * <p>
 * 本类作为薄包装层，将路径解析、用户名提取等 LoomAgent 特有逻辑
 * 委托给 {@link MavenOperations} 核心执行引擎。
 */
public class DefaultMavenTool implements IMavenTool {

    private final MavenOperations mavenOps;
    private final String fileBasePath;

    public DefaultMavenTool(MavenProperty properties, String fileBasePath) {
        this.fileBasePath = fileBasePath != null ? fileBasePath : ".local/file";
        this.mavenOps = new MavenOperations(
                properties.getMavenHome(),
                properties.getLocalRepository(),
                properties.getMaxOutputLines(),
                properties.getDefaultTimeoutMs()
        );
    }

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

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在用户目录 " + workDir + " 中未找到 pom.xml，请指定正确的 pomPath 或 workingDir";

        return mavenOps.execute(goals, workDir, pomFile, properties, timeoutMs);
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

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("compile");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        if (skipTests != null && skipTests) {
            props.put("skipTests", "true");
        }
        return mavenOps.execute(goals, workDir, pomFile, props, null);
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

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("package");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        boolean skip = skipTests == null || skipTests;
        if (skip) {
            props.put("skipTests", "true");
        }
        return mavenOps.execute(goals, workDir, pomFile, props, null);
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

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("test");
        Map<String, String> props = new LinkedHashMap<>(properties != null ? properties : Map.of());
        if (testPattern != null && !testPattern.isBlank()) {
            props.put("test", testPattern);
        }
        return mavenOps.execute(goals, workDir, pomFile, props, null);
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

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        List<String> goals = List.of("dependency:tree");
        Map<String, String> props = new LinkedHashMap<>();
        if (includeScope != null && !includeScope.isBlank()) {
            props.put("includesScope", includeScope);
        }
        return mavenOps.execute(goals, workDir, pomFile, props, null);
    }

    @Tool(description = "验证项目结构是否正确（mvn validate）。检查 pom.xml 和必要资源是否存在。")
    @Override
    public String mavenValidate(
            @ToolParam(description = "pom.xml 路径（可选，默认在当前工作目录查找）", required = false) String pomPath,
            @ToolParam(description = "工作目录（可选，默认为用户文件目录 .local/file/{username}/）", required = false) String workingDir,
            ToolContext toolContext) {

        String username = getUsername(toolContext);
        if (username == null) return "错误：无法获取用户名";

        File workDir = mavenOps.resolveWorkingDir(workingDir, username, this.fileBasePath);
        File pomFile = mavenOps.resolvePomFile(pomPath, username, this.fileBasePath, workDir);
        if (pomFile == null || !pomFile.exists()) return "错误：在工作目录 " + workDir + " 中未找到 pom.xml";

        return mavenOps.execute(List.of("validate"), workDir, pomFile, Map.of(), null);
    }

    // ==================== Helper ====================

    /**
     * 获取用户名
     */
    private String getUsername(ToolContext toolContext) {
        if (toolContext == null) return null;
        Object u = toolContext.getContext().get("username");
        if (u == null || u.toString().isBlank()) return null;
        return u.toString();
    }
}
