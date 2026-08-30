package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import cn.wubo.spring.ai.loom.agent.tool.ToolGroup;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

@ToolGroup("maven")
/**
 * Maven 构建工具接口。
 * <p>
 * 基于 maven-invoker API 提供 6 个工具方法，
 * 使 LLM 能够直接执行 Maven 构建、打包、测试和依赖分析操作，
 * 无需依赖系统 shell 或 PATH 上的 {@code mvn} 命令。
 */
public interface IMavenTool extends IEmbedTool {

    /**
     * 执行任意 Maven 命令（通用入口）
     */
    String mavenExecute(List<String> goals, String pomPath, String workingDir,
                        Map<String, String> properties, Long timeoutMs, ToolContext toolContext);

    /**
     * 编译项目（mvn compile）
     */
    String mavenBuild(String pomPath, String workingDir,
                      Map<String, String> properties, Boolean skipTests, ToolContext toolContext);

    /**
     * 打包项目（mvn package）
     */
    String mavenPackage(String pomPath, String workingDir,
                        Map<String, String> properties, Boolean skipTests, ToolContext toolContext);

    /**
     * 运行测试（mvn test）
     */
    String mavenTest(String pomPath, String workingDir, String testPattern,
                     Map<String, String> properties, ToolContext toolContext);

    /**
     * 查看依赖树（mvn dependency:tree）
     */
    String mavenDependencyTree(String pomPath, String workingDir,
                               String includeScope, ToolContext toolContext);

    /**
     * 验证项目（mvn validate）
     */
    String mavenValidate(String pomPath, String workingDir, ToolContext toolContext);
}
