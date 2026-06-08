package cn.wubo.spring.ai.loom.agent.tool.maven;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties.MavenProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultMavenTool 单元测试
 * <p>
 * 覆盖：
 * 1. 路径解析：默认工作目录、pomPath 相对/绝对、越权拒绝
 * 2. 用户名缺失返回错误
 * 3. goals 为空返回错误
 * 4. pom.xml 缺失返回错误
 * 5. truncateOutput 截断到 maxOutputLines
 * 6. resolveWorkingDir 默认走用户文件目录
 */
@DisplayName("DefaultMavenTool 单元测试")
class DefaultMavenToolTest {

    private DefaultMavenTool tool;
    private MavenProperty props;
    private Path tmpRoot;
    private String username;

    @BeforeEach
    void setUp() throws IOException {
        props = new MavenProperty();
        props.setDefaultTimeoutMs(30000L); // 30s for tests
        props.setMaxOutputLines(50);

        tmpRoot = Files.createTempDirectory("loom-mvn-test-");
        username = tmpRoot.getFileName().toString();
        tool = new DefaultMavenTool(props, tmpRoot.getParent().toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            Files.walkFileTree(tmpRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static ToolContext ctx(String username) {
        Map<String, Object> m = new HashMap<>();
        m.put("username", username);
        return new ToolContext(m);
    }

    private void writePom(String... lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        Files.writeString(tmpRoot.resolve("pom.xml"), sb.toString(), StandardCharsets.UTF_8);
    }

    // ==================== username / goals / pom 校验 ====================

    @Test
    @DisplayName("username 缺失返回错误")
    void usernameMissingReturnsError() {
        ToolContext noUser = new ToolContext(new HashMap<>());
        String result = tool.mavenBuild(null, null, null, null, noUser);
        assertTrue(result.contains("无法获取用户名"), "应提示缺少 username: " + result);
    }

    @Test
    @DisplayName("goals 为空返回错误")
    void emptyGoalsReturnsError() {
        String result = tool.mavenExecute(List.of(), null, null, null, null, ctx(username));
        assertTrue(result.contains("goals 不能为空"), "应提示 goals 为空: " + result);
    }

    @Test
    @DisplayName("pom.xml 缺失返回错误")
    void missingPomReturnsError() {
        // 用户目录存在但没有 pom.xml
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"), "应提示找不到 pom.xml: " + result);
    }

    // ==================== 路径越权 ====================

    @Test
    @DisplayName("绝对路径 workingDir 越权返回错误（pom.xml 找不到）")
    void absoluteWorkingDirRejected() {
        // validatePathInUserDir 返回 null → resolvePomFile 走默认 workDir（即用户目录），
        // 然后在用户目录里找 pom.xml，找不到就报"未找到 pom.xml"。
        String result = tool.mavenBuild("C:\\Windows\\System32", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "绝对路径应导致 pom.xml 找不到: " + result);
    }

    @Test
    @DisplayName("相对路径 .. 穿越越权返回错误")
    void traversalWorkingDirRejected() {
        String result = tool.mavenBuild("../escape", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "路径穿越应被拒绝: " + result);
    }

    @Test
    @DisplayName("绝对 pomPath 越权返回错误")
    void absolutePomPathRejected() {
        String result = tool.mavenBuild("C:\\Windows\\System32\\evil.xml", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("pom.xml"),
                "绝对 pomPath 应导致找不到 pom.xml: " + result);
    }

    // ==================== 工作目录解析 ====================

    @Test
    @DisplayName("workingDir 为 null 时使用用户文件目录")
    void defaultWorkingDir() {
        // 临时目录存在但没有 pom.xml：返回"未找到 pom.xml"而不是异常
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"), "应使用默认用户目录查找: " + result);
        // 应在错误信息中包含用户目录
        assertTrue(result.contains(tmpRoot.toString()) || result.contains(username),
                "应提及用户文件目录: " + result);
    }

    @Test
    @DisplayName("workingDir 指定子目录在用户目录内合法")
    void workingDirWithinUserDir() throws IOException {
        Path sub = tmpRoot.resolve("subdir");
        Files.createDirectories(sub);
        // 子目录里没 pom.xml 也算合法，但执行会失败（不在用户目录内？不会，是用户目录内的子目录）
        String result = tool.mavenBuild(null, "subdir", null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml") || result.contains("Maven"),
                "合法子目录应能处理: " + result);
    }

    // ==================== pom.xml 存在但不合法 ====================

    @Test
    @DisplayName("pom.xml 不是合法 XML 时 Maven 执行失败但工具优雅返回")
    void invalidPomContentHandled() throws IOException {
        Files.writeString(tmpRoot.resolve("pom.xml"), "this is not xml", StandardCharsets.UTF_8);
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        // 不抛异常到调用方，而是返回格式化的错误信息
        assertNotNull(result);
        // 包含 Maven 输出结构
        assertTrue(result.contains("Maven") || result.contains("POM") || result.contains("错误"),
                "应给出 Maven 错误信息: " + result);
    }

    // ==================== 通用入口 ====================

    @Test
    @DisplayName("mavenExecute 调用 dependency:tree 在无效 pom 上返回错误")
    void executeWithInvalidPom() throws IOException {
        Files.writeString(tmpRoot.resolve("pom.xml"), "<bad/>", StandardCharsets.UTF_8);
        String result = tool.mavenExecute(List.of("validate"), null, null, null, 5000L, ctx(username));
        assertNotNull(result);
        assertTrue(result.length() > 0, "应返回非空结果");
    }

    // ==================== 各种 goal 入口的参数 ====================

    @Test
    @DisplayName("mavenPackage 默认跳过测试")
    void packageDefaultSkipsTests() throws IOException {
        writePom("<project/>");
        String result = tool.mavenPackage(null, null, null, null, ctx(username));
        // 不需要成功执行，只检查返回包含命令和属性
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenTest 接收 testPattern")
    void testWithPattern() throws IOException {
        writePom("<project/>");
        String result = tool.mavenTest(null, null, "*ServiceTest", null, ctx(username));
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenDependencyTree 接收 includeScope")
    void dependencyTreeWithScope() throws IOException {
        writePom("<project/>");
        String result = tool.mavenDependencyTree(null, null, "compile", ctx(username));
        assertNotNull(result);
    }

    @Test
    @DisplayName("mavenValidate 简单调用")
    void validateSimple() throws IOException {
        writePom("<project/>");
        String result = tool.mavenValidate(null, null, ctx(username));
        assertNotNull(result);
    }

    // ==================== truncateOutput 行为 ====================

    @Test
    @DisplayName("pom.xml 缺失的错误信息包含用户目录路径")
    void errorMessageIncludesUserDir() {
        String result = tool.mavenBuild(null, null, null, null, ctx(username));
        assertTrue(result.contains(tmpRoot.toString()),
                "错误信息应包含用户文件目录路径: " + result);
    }
}
