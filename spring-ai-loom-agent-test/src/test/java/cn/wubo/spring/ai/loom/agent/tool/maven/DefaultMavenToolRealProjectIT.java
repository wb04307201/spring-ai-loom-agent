package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import cn.wubo.spring.ai.loom.agent.tool.maven.MavenHomeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 端到端集成测试：在用户实际的 .local/file/username/sql-forge-demo/sql-forge-demo 项目上
 * 调用 mavenBuild，验证：
 * <ol>
 * <li>不配置 mavenHome 也能成功调用 Maven（依赖自动探测）</li>
 * <li>输出中不再出现 "Error configuring command line"（找不到 mvn 的旧症状）</li>
 * </ol>
 * 注：目标项目本身的 Java 代码可能不通过编译，但那是项目问题，不是工具问题——
 * 本测试只验证工具能正确执行 Maven 进程。
 */
@DisplayName("DefaultMavenTool 真实项目集成测试")
class DefaultMavenToolRealProjectIT {

 private static final String REAL_PROJECT = "C:\\developer\\IdeaProjects\\spring-ai-loom-agent\\.local\\file\\username\\sql-forge-demo\\sql-forge-demo";

 private static ToolContext ctx(String username) {
 Map<String, Object> m = new HashMap<>();
 m.put("username", username);
 return new ToolContext(m);
 }

 @Test
 @DisplayName("不配置 mavenHome 也能成功调用 Maven（依赖自动探测）")
 void autoDetectCanInvokeMaven() {
 // 目标项目必须存在才执行测试
 File pom = new File(REAL_PROJECT, "pom.xml");
 assumeTrue(pom.isFile(), "跳过：真实项目 " + REAL_PROJECT + " 不存在");

 MavenProperty props = new MavenProperty();
 props.setDefaultTimeoutMs(120_000L);
 props.setMaxOutputLines(50);
 // 注意：故意不设置 mavenHome，模拟 application.yml 现状

 DefaultMavenTool tool = new DefaultMavenTool(props,
 "C:\\developer\\IdeaProjects\\spring-ai-loom-agent\\.local\\file");

 String result = tool.mavenBuild(
 null, // pomPath
 "sql-forge-demo/sql-forge-demo", // workingDir
 null, null, ctx("username"));

 // 关键断言：不能出现 "Error configuring command line"
 // （这是找不到 mvn 时 maven-invoker 抛的异常）
 org.junit.jupiter.api.Assertions.assertFalse(
 result.contains("Error configuring command line"),
 "不应再出现找不到 mvn 的错误。实际返回：\n" + result);

 // 工具应成功启动 Maven 进程（出现 Maven 输出或返回非 0 但带 Maven 错误信息）
 org.junit.jupiter.api.Assertions.assertTrue(
 result.contains("Maven 执行"),
 "应返回 Maven 执行结果。实际返回：\n" + result);
 }

 @Test
 @DisplayName("显式配置 mavenHome=自动探测到的路径也能跑通")
 void explicitMavenHomeAlsoWorks() {
 File pom = new File(REAL_PROJECT, "pom.xml");
 assumeTrue(pom.isFile(), "跳过：真实项目 " + REAL_PROJECT + " 不存在");

 // 让工具先自己探测一次
 String autoHome = MavenHomeResolver.resolve(null);
 assumeTrue(autoHome != null, "跳过：自动探测未发现 Maven Home");

 MavenProperty props = new MavenProperty();
 props.setDefaultTimeoutMs(120_000L);
 props.setMaxOutputLines(50);
 props.setMavenHome(autoHome);

 DefaultMavenTool tool = new DefaultMavenTool(props,
 "C:\\developer\\IdeaProjects\\spring-ai-loom-agent\\.local\\file");

 String result = tool.mavenBuild(
 null,
 "sql-forge-demo/sql-forge-demo",
 null, null, ctx("username"));

 org.junit.jupiter.api.Assertions.assertFalse(
 result.contains("Error configuring command line"),
 "显式配置 mavenHome 后不应再出现找不到 mvn 的错误。实际返回：\n" + result);
 }
}
