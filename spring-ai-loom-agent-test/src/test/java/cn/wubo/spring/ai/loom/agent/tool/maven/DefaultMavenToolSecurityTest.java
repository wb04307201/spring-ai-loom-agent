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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultMavenTool} 安全相关单元测试
 * <p>
 * 关注：
 * <ol>
 *   <li>username 为 null / 空字符串 时拒绝</li>
 *   <li>绝对路径 + .. 越权通过 symlink 防御</li>
 *   <li>工具构造时 fileBasePath 为 null 也能容错</li>
 * </ol>
 */
@DisplayName("DefaultMavenTool 安全测试")
class DefaultMavenToolSecurityTest {

    private DefaultMavenTool tool;
    private MavenProperty props;
    private Path tmpRoot;
    private String username;

    @BeforeEach
    void setUp() throws IOException {
        props = new MavenProperty();
        props.setDefaultTimeoutMs(30000L);
        props.setMaxOutputLines(50);

        tmpRoot = Files.createTempDirectory("loom-mvn-sec-");
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

    // ==================== username 校验 ====================

    @Test
    @DisplayName("username 为 null 返回错误")
    void nullUsernameRejected() {
        ToolContext noUser = new ToolContext(new HashMap<>());
        String result = tool.mavenBuild(null, null, null, null, noUser);
        assertTrue(result.contains("无法获取用户名"), "应提示缺少 username: " + result);
    }

    @Test
    @DisplayName("username 为空字符串返回错误")
    void blankUsernameRejected() {
        ToolContext blankUser = ctx("");
        String result = tool.mavenBuild(null, null, null, null, blankUser);
        assertTrue(result.contains("无法获取用户名"), "应提示缺少 username: " + result);
    }

    @Test
    @DisplayName("username 仅含空白返回错误")
    void whitespaceUsernameRejected() {
        ToolContext wsUser = ctx("   ");
        String result = tool.mavenBuild(null, null, null, null, wsUser);
        assertTrue(result.contains("无法获取用户名"), "应提示缺少 username: " + result);
    }

    // ==================== 越权拒绝 ====================

    @Test
    @DisplayName("绝对 workingDir 越权 → 视为未找到 pom")
    void absoluteWorkingDirRejected() {
        String result = tool.mavenBuild("C:\\Windows\\System32", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"),
                "绝对路径应被拒绝（pom.xml 找不到）: " + result);
    }

    @Test
    @DisplayName(".. 穿越 workingDir 越权 → 视为未找到 pom")
    void traversalWorkingDirRejected() {
        String result = tool.mavenBuild("../escape", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"),
                ".. 越权应被拒绝: " + result);
    }

    @Test
    @DisplayName("绝对 pomPath 越权 → 视为未找到 pom")
    void absolutePomPathRejected() {
        String result = tool.mavenBuild("C:\\Windows\\System32\\evil.xml", null, null, null, ctx(username));
        assertTrue(result.contains("未找到 pom.xml"),
                "绝对 pomPath 应被拒绝: " + result);
    }

    @Test
    @DisplayName("symlink 越界 workingDir → 视为未找到 pom")
    void symlinkEscapeRejected() throws IOException {
        // 准备 userDir 外的真实目录，里面放一个 pom.xml
        Path outside = tmpRoot.getParent().resolve("outside-mvn");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("pom.xml"),
                "<?xml version=\"1.0\"?><project></project>", StandardCharsets.UTF_8);
        try {
            // userDir/evil-link → outside（symlink 在 workingDir 链上）
            Files.createSymbolicLink(tmpRoot.resolve("evil-link"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            System.out.println("[跳过 symlink 测试，OS 不支持或权限不足] " + e.getMessage());
            Files.delete(outside.resolve("pom.xml"));
            Files.delete(outside);
            return;
        }

        try {
            String result = tool.mavenBuild(null, "evil-link", null, null, ctx(username));
            // symlink 越界应被拒绝 → pom.xml 找不到
            assertFalse(result.contains("BUILD SUCCESS"),
                    "symlink 越界不应能找到 pom.xml: " + result);
            assertTrue(result.contains("未找到 pom.xml"),
                    "symlink 越界应被拒绝: " + result);
        } finally {
            Files.deleteIfExists(tmpRoot.resolve("evil-link"));
            Files.deleteIfExists(outside.resolve("pom.xml"));
            Files.deleteIfExists(outside);
        }
    }
}
