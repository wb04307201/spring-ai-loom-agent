package cn.wubo.spring.ai.loom.agent.tool.compile;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.CompileProperty;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultCompileAndDeployTool} 单元测试。
 * <p>
 * 覆盖纯函数行为（不实际跑 git / mvn / docker）：
 * <ol>
 *   <li>{@code writeDockerfile} 写入的内容格式正确，包含 baseImage、jar 名、EXPOSE 端口</li>
 *   <li>{@code findBuiltJar} 在 target/ 下能挑出 Spring Boot fat jar（按体积排序），
 *       并跳过 {@code .original.jar} / {@code -sources.jar} / {@code -javadoc.jar}</li>
 *   <li>username 从 ToolContext 正确解析</li>
 *   <li>{@code deriveRepoName} 处理 https URL / .git 后缀 / 末尾斜杠</li>
 *   <li>无效参数（空 gitUrl / 空 username）立即返回失败结果，不进入子流程</li>
 * </ol>
 */
@DisplayName("DefaultCompileAndDeployTool 单元测试")
class DefaultCompileAndDeployToolTest {

    private DefaultCompileAndDeployTool tool;
    private CompileProperty compile;

    @BeforeEach
    void setUp() {
        compile = new CompileProperty();
        compile.setDefaultPort(9090);
        compile.setBaseImage("eclipse-temurin:17-jre-alpine");
        tool = new DefaultCompileAndDeployTool(compile, null, ".local/file");
    }

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        return new ToolContext(m);
    }

    @Test
    @DisplayName("writeDockerfile 写入 FROM/EXPOSE/ENTRYPOINT")
    void writeDockerfile_content(@TempDir Path projectDir) throws Exception {
        // 模拟一个 target/ 下的 jar
        Files.createDirectories(projectDir.resolve("target"));
        File jar = projectDir.resolve("target").resolve("demo-0.0.1-SNAPSHOT.jar").toFile();
        Files.writeString(jar.toPath(), "fake-jar-content");

        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod("writeDockerfile", Path.class, File.class);
        m.setAccessible(true);
        File dockerfile = (File) m.invoke(tool, projectDir, jar);

        assertTrue(dockerfile.exists());
        String content = Files.readString(dockerfile.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("FROM eclipse-temurin:17-jre-alpine"), "缺 FROM 行: " + content);
        assertTrue(content.contains("COPY target/demo-0.0.1-SNAPSHOT.jar app.jar"), "缺 COPY 行: " + content);
        assertTrue(content.contains("EXPOSE 9090"), "缺 EXPOSE 行: " + content);
        assertTrue(content.contains("ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]"), "缺 ENTRYPOINT 行: " + content);
    }

    @Test
    @DisplayName("findBuiltJar 选体积最大的 jar，跳过 .original/-sources/-javadoc")
    void findBuiltJar_picksLargest(@TempDir Path projectDir) throws Exception {
        Path target = projectDir.resolve("target");
        Files.createDirectories(target);
        // 普通 jar 体积较小
        Files.writeString(target.resolve("demo-0.0.1-SNAPSHOT.jar.original"), "orig");
        Files.writeString(target.resolve("demo-0.0.1-SNAPSHOT-sources.jar"), "src");
        Files.writeString(target.resolve("demo-0.0.1-SNAPSHOT-javadoc.jar"), "javadoc");
        // 小 jar
        Files.writeString(target.resolve("small.jar"), "x");
        // 大 jar（Spring Boot fat jar）
        byte[] big = new byte[8192];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        Files.write(target.resolve("demo-0.0.1-SNAPSHOT.jar"), big);

        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod("findBuiltJar", Path.class);
        m.setAccessible(true);
        File jar = (File) m.invoke(tool, projectDir);
        assertNotNull(jar);
        assertEquals("demo-0.0.1-SNAPSHOT.jar", jar.getName(),
                "应当选 Spring Boot fat jar, not original/sources/javadoc");
    }

    @Test
    @DisplayName("compileAndDeploy 缺 gitUrl 立即失败")
    void compileAndDeploy_emptyGitUrl() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of(), ctx("alice"));
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("gitUrl"));
        assertTrue(r.steps().isEmpty());
    }

    @Test
    @DisplayName("compileAndDeploy username 为空立即失败")
    void compileAndDeploy_emptyUsername() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of("gitUrl", "https://gitee.com/xxx/demo.git"),
                ctx(null));
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("用户名"));
    }

    @Test
    @DisplayName("compileAndDeploy username 为空字符串同样视为缺失")
    void compileAndDeploy_blankUsername() {
        CompileAndDeployResult r = tool.compileAndDeploy(
                java.util.Map.of("gitUrl", "https://gitee.com/xxx/demo.git"),
                ctx(""));
        assertFalse(r.success());
    }

    @Test
    @DisplayName("deriveRepoName 正确处理 https / .git 后缀 / 末尾斜杠")
    void deriveRepoName_variants() throws Exception {
        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod("deriveRepoName", String.class);
        m.setAccessible(true);
        assertEquals("demo", m.invoke(tool, "https://gitee.com/wb04307201/demo.git"));
        assertEquals("demo", m.invoke(tool, "https://gitee.com/wb04307201/demo"));
        assertEquals("demo", m.invoke(tool, "https://gitee.com/wb04307201/demo.git/"));
        // 仓库名为空时回退到 "repo"
        assertEquals("repo", m.invoke(tool, "https://gitee.com/wb04307201/.git"));
        // ssh URL 也支持
        assertEquals("demo", m.invoke(tool, "git@gitee.com:wb04307201/demo.git"));
    }

    @Test
    @DisplayName("LoomAgentProperties 持有 compile 配置，默认开启")
    void properties_defaultEnabled() {
        LoomAgentProperties p = new LoomAgentProperties();
        assertNotNull(p.getCompile());
        assertTrue(p.getCompile().isEnabled());
        assertEquals(8080, p.getCompile().getDefaultPort());
        assertEquals("eclipse-temurin:17-jre-alpine", p.getCompile().getBaseImage());
        assertNotNull(p.getCompile().getExtraRunArgs());
        assertTrue(p.getCompile().getExtraRunArgs().isEmpty());
    }

    @Test
    @DisplayName("CompileProperty 默认超时合理")
    void compileProperty_saneDefaults() {
        CompileProperty p = new CompileProperty();
        assertEquals(600000L, p.getMavenTimeoutMs());
        assertEquals(600000L, p.getDockerBuildTimeoutMs());
        assertEquals(60000L, p.getDockerRunTimeoutMs());
        assertEquals(60000L, p.getHealthCheckMaxWaitMs());
        assertEquals(2000L, p.getHealthCheckIntervalMs());
        assertFalse(p.isKeepWorkspace());
    }

    @Test
    @DisplayName("CompileProperty.imageTemplates 默认预置 java17/java21/nginx/python3")
    void imageTemplates_defaultIncludesCommonAliases() {
        CompileProperty p = new CompileProperty();
        assertNotNull(p.getImageTemplates(), "imageTemplates 不应为 null");
        assertTrue(p.getImageTemplates().containsKey("java17"), "缺 java17 模板");
        assertTrue(p.getImageTemplates().containsKey("java21"), "缺 java21 模板");
        assertTrue(p.getImageTemplates().containsKey("nginx"), "缺 nginx 模板");
        assertTrue(p.getImageTemplates().containsKey("python3"), "缺 python3 模板");

        LoomAgentProperties.CompileProperty.ImageTemplate java17 = p.getImageTemplates().get("java17");
        assertEquals("eclipse-temurin:17-jre-alpine", java17.getImage());
        assertEquals(List.of("java", "-jar", "app.jar"), java17.getCommand());

        LoomAgentProperties.CompileProperty.ImageTemplate nginx = p.getImageTemplates().get("nginx");
        assertEquals("nginx:1.27-alpine", nginx.getImage());
        assertEquals(List.of("nginx", "-g", "daemon off;"), nginx.getCommand());
    }

    @Test
    @DisplayName("CompileAndDeployResult ok/fail 工厂方法")
    void resultFactories() {
        CompileAndDeployResult ok = CompileAndDeployResult.ok("/w", "repo", "main", "img", "ctr", 8080,
                "http://localhost:8080", "/", List.of("step1"));
        assertTrue(ok.success());
        assertEquals("http://localhost:8080", ok.accessUrl());
        assertNull(ok.errorMessage());

        CompileAndDeployResult fail = CompileAndDeployResult.fail("/w", "repo", "img", "ctr", 8080, "/",
                List.of("step1"), "boom");
        assertFalse(fail.success());
        assertEquals("boom", fail.errorMessage());
        assertNull(fail.accessUrl());
    }

    @Test
    @DisplayName("resolveEffectiveProjectDir: 单模块直接返回")
    void resolveEffective_singleModule(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class);
        m.setAccessible(true);
        Path result = (Path) m.invoke(tool, projectDir, "demo");
        assertEquals(projectDir, result);
    }

    @Test
    @DisplayName("resolveEffectiveProjectDir: 多模块时优先选与 repoName 同名的子目录")
    void resolveEffective_multiModule_preferNameMatch(@TempDir Path projectDir) throws Exception {
        // 模拟 sql-forge-demo 仓库：根无 pom，spring-ai-chat-demo/ + sql-forge-demo/ 两个子模块
        Files.createDirectories(projectDir.resolve("spring-ai-chat-demo"));
        Files.writeString(projectDir.resolve("spring-ai-chat-demo").resolve("pom.xml"), "<project/>");
        Files.createDirectories(projectDir.resolve("sql-forge-demo"));
        Files.writeString(projectDir.resolve("sql-forge-demo").resolve("pom.xml"), "<project/>");

        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class);
        m.setAccessible(true);
        Path result = (Path) m.invoke(tool, projectDir, "sql-forge-demo");
        assertEquals(projectDir.resolve("sql-forge-demo"), result,
                "应当挑与仓库名同名的子目录，而不是按字母序选 spring-ai-chat-demo");
    }

    @Test
    @DisplayName("resolveEffectiveProjectDir: 没有名字匹配时按字母序兜底")
    void resolveEffective_multiModule_fallback(@TempDir Path projectDir) throws Exception {
        Files.createDirectories(projectDir.resolve("zeta"));
        Files.writeString(projectDir.resolve("zeta").resolve("pom.xml"), "<project/>");
        Files.createDirectories(projectDir.resolve("alpha"));
        Files.writeString(projectDir.resolve("alpha").resolve("pom.xml"), "<project/>");

        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "resolveEffectiveProjectDir", Path.class, String.class);
        m.setAccessible(true);
        Path result = (Path) m.invoke(tool, projectDir, "some-other-name");
        assertEquals(projectDir.resolve("alpha"), result, "无名字匹配时按字母序选 alpha");
    }

    @Test
    @DisplayName("findBuiltJar 在 effectiveDir/target/ 下找 jar，不在父目录中找")
    void findBuiltJar_effectiveDirOnly(@TempDir Path projectDir) throws Exception {
        // 模拟：根目录有 target/ 但里面是另一个模块的产物（应当忽略）
        Path rootTarget = projectDir.resolve("target");
        Files.createDirectories(rootTarget);
        Files.writeString(rootTarget.resolve("ignored.jar"), "x");
        // 真正的子模块有自己的 target/ 和 fat jar
        Path sub = projectDir.resolve("sql-forge-demo");
        Path subTarget = sub.resolve("target");
        Files.createDirectories(subTarget);
        byte[] big = new byte[2048];
        Files.write(subTarget.resolve("sql-forge-demo-0.0.1.jar"), big);

        File jar = tool.findBuiltJar(sub);
        assertNotNull(jar);
        assertEquals("sql-forge-demo-0.0.1.jar", jar.getName(),
                "传入 effectiveDir 时只在该目录的 target/ 下找");
    }

    @Test
    @DisplayName("wrapForWindows: .cmd 在 Windows 上用 cmd.exe /c 包装")
    void wrapForWindows_cmdWrapped() throws Exception {
        // 强制 Windows 行为：测试机是 Windows，env 直接判断
        // 用反射看 static helper 的行为
        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "wrapForWindows", String.class, java.util.List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> cmd = (java.util.List<String>) m.invoke(null,
                "C:\\maven\\bin\\mvn.cmd", java.util.List.of("-B", "clean"));
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertEquals("cmd.exe", cmd.get(0));
            assertEquals("/c", cmd.get(1));
            assertEquals("C:\\maven\\bin\\mvn.cmd", cmd.get(2));
            assertEquals("-B", cmd.get(3));
        } else {
            assertEquals("C:\\maven\\bin\\mvn.cmd", cmd.get(0));
        }
    }

    @Test
    @DisplayName("wrapForWindows: .exe 不需要包装")
    void wrapForWindows_exeNotWrapped() throws Exception {
        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "wrapForWindows", String.class, java.util.List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> cmd = (java.util.List<String>) m.invoke(null,
                "C:\\Program Files\\Docker\\docker.exe", java.util.List.of("ps"));
        // 不管是 Win 还是 Unix，.exe 都不该被包成 cmd.exe /c
        assertFalse(cmd.get(0).equals("cmd.exe"),
                ".exe 不应该被 cmd.exe /c 包装");
    }

    @Test
    @DisplayName("createTempBatch: 生成的 .bat 含 call、@echo off 和所有参数")
    void createTempBatch_containsCallAndArgs() throws Exception {
        Method m = DefaultCompileAndDeployTool.class.getDeclaredMethod(
                "createTempBatch", java.util.List.class);
        m.setAccessible(true);
        File bat = (File) m.invoke(null,
                java.util.List.of("C:\\maven\\bin\\mvn.cmd", "-B", "-e", "clean", "package"));
        assertNotNull(bat);
        try {
            String content = Files.readString(bat.toPath(), StandardCharsets.UTF_8);
            assertTrue(content.contains("@echo off"), "应含 @echo off: " + content);
            assertTrue(content.contains("call"), "应含 call: " + content);
            assertTrue(content.contains("C:\\maven\\bin\\mvn.cmd"), "应含 mvn.cmd 路径: " + content);
            assertTrue(content.contains("-B"), "应含 -B 参数: " + content);
            assertTrue(content.contains("clean"), "应含 clean: " + content);
        } finally {
            bat.delete();
        }
    }
}
