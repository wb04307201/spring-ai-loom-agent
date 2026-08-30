package cn.wubo.loom.maven.mcp;

import cn.wubo.loom.maven.core.MavenOperations;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maven MCP 服务端。basePath 和 mavenHome 从配置读取。
 */
public class LoomMavenMcpService {

    private final MavenOperations mavenOps;
    private final Path basePath;

    public LoomMavenMcpService(LoomMavenMcpProperties props) {
        this.basePath = Paths.get(props.getBasePath()).toAbsolutePath().normalize();
        this.mavenOps = new MavenOperations(
                props.getMavenHome(),
                props.getLocalRepository(),
                props.getMaxOutputLines(),
                props.getDefaultTimeoutMs()
        );
    }

    @McpTool(name = "maven_execute", description = "执行 Maven 命令（通用入口）。可指定任意 goals，如 clean、package、dependency:tree 等。")
    public String mavenExecute(
            @ToolParam(description = "Maven goals 列表，如 [\"clean\", \"package\"]") List<String> goals,
            @ToolParam(description = "pom.xml 路径（相对或绝对）", required = false) String pomPath,
            @ToolParam(description = "工作目录（相对或绝对）", required = false) String workingDir,
            @ToolParam(description = "Maven 属性", required = false) Map<String, String> properties,
            @ToolParam(description = "超时毫秒数", required = false) Long timeoutMs) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        return mavenOps.execute(goals, workDir, pomFile, properties, timeoutMs);
    }

    @McpTool(name = "maven_build", description = "编译项目（mvn compile）")
    public String mavenBuild(
            @ToolParam(description = "pom.xml 路径", required = false) String pomPath,
            @ToolParam(description = "工作目录", required = false) String workingDir,
            @ToolParam(description = "Maven 属性", required = false) Map<String, String> properties,
            @ToolParam(description = "是否跳过测试", required = false) Boolean skipTests) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        List<String> goals = new ArrayList<>(List.of("compile"));
        Map<String, String> props = properties != null ? new java.util.HashMap<>(properties) : new java.util.HashMap<>();
        if (skipTests != null && skipTests) {
            props.put("skipTests", "true");
        }
        return mavenOps.execute(goals, workDir, pomFile, props.isEmpty() ? null : props, null);
    }

    @McpTool(name = "maven_package", description = "打包项目（mvn package），默认跳过测试")
    public String mavenPackage(
            @ToolParam(description = "pom.xml 路径", required = false) String pomPath,
            @ToolParam(description = "工作目录", required = false) String workingDir,
            @ToolParam(description = "Maven 属性", required = false) Map<String, String> properties,
            @ToolParam(description = "是否跳过测试", required = false) Boolean skipTests) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        List<String> goals = new ArrayList<>(List.of("package"));
        Map<String, String> props = properties != null ? new java.util.HashMap<>(properties) : new java.util.HashMap<>();
        if (skipTests == null || skipTests) {
            props.put("skipTests", "true");
        }
        return mavenOps.execute(goals, workDir, pomFile, props.isEmpty() ? null : props, null);
    }

    @McpTool(name = "maven_test", description = "运行单元测试（mvn test）")
    public String mavenTest(
            @ToolParam(description = "pom.xml 路径", required = false) String pomPath,
            @ToolParam(description = "工作目录", required = false) String workingDir,
            @ToolParam(description = "测试模式，如 *ServiceTest", required = false) String testPattern,
            @ToolParam(description = "Maven 属性", required = false) Map<String, String> properties) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        List<String> goals = new ArrayList<>(List.of("test"));
        Map<String, String> props = properties != null ? new java.util.HashMap<>(properties) : new java.util.HashMap<>();
        if (testPattern != null && !testPattern.isBlank()) {
            props.put("test", testPattern);
        }
        return mavenOps.execute(goals, workDir, pomFile, props.isEmpty() ? null : props, null);
    }

    @McpTool(name = "maven_dependency_tree", description = "查看依赖树（mvn dependency:tree）")
    public String mavenDependencyTree(
            @ToolParam(description = "pom.xml 路径", required = false) String pomPath,
            @ToolParam(description = "工作目录", required = false) String workingDir,
            @ToolParam(description = "依赖范围：compile/runtime/test/provided", required = false) String includeScope) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        List<String> goals = new ArrayList<>(List.of("dependency:tree"));
        Map<String, String> props = null;
        if (includeScope != null && !includeScope.isBlank()) {
            props = new java.util.HashMap<>();
            props.put("includesScope", includeScope);
        }
        return mavenOps.execute(goals, workDir, pomFile, props, null);
    }

    @McpTool(name = "maven_validate", description = "验证项目结构（mvn validate）")
    public String mavenValidate(
            @ToolParam(description = "pom.xml 路径", required = false) String pomPath,
            @ToolParam(description = "工作目录", required = false) String workingDir) {
        File workDir = resolveWorkingDir(workingDir);
        File pomFile = resolvePomFile(pomPath, workDir);
        return mavenOps.execute(List.of("validate"), workDir, pomFile, null, null);
    }

    private File resolveWorkingDir(String workingDir) {
        if (workingDir != null && !workingDir.isBlank()) {
            File dir = new File(workingDir);
            return dir.isAbsolute() ? dir : basePath.resolve(workingDir).toFile();
        }
        return basePath.toFile();
    }

    private File resolvePomFile(String pomPath, File workDir) {
        if (pomPath != null && !pomPath.isBlank()) {
            File pom = new File(pomPath);
            return pom.isAbsolute() ? pom : new File(workDir, pomPath);
        }
        return new File(workDir, "pom.xml");
    }
}
