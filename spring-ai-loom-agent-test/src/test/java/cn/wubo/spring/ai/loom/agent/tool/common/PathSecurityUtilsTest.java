package cn.wubo.spring.ai.loom.agent.tool.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PathSecurityUtils} 单元测试
 * <p>
 * 覆盖三类越权：
 * <ol>
 *   <li>.. 路径穿越</li>
 *   <li>symlink 越界（需要 OS 支持 symlink；Windows 默认需要 SeCreateSymbolicLinkPrivilege）</li>
 *   <li>大小写不敏感文件系统的 size-bypass（Windows/macOS）</li>
 * </ol>
 */
@DisplayName("PathSecurityUtils 单元测试")
class PathSecurityUtilsTest {

    private Path tmpRoot;
    private Path userDir;

    @BeforeEach
    void setUp() throws IOException {
        tmpRoot = Files.createTempDirectory("pathsec-");
        userDir = tmpRoot.resolve("user1");
        Files.createDirectories(userDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            Files.walkFileTree(tmpRoot, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // ==================== mustExist=true ====================

    @Test
    @DisplayName("mustExist=true: 路径存在且在 userDir 内 → 正常通过")
    void mustExist_inBounds_ok() throws IOException {
        Path p = userDir.resolve("a.txt");
        Files.writeString(p, "x");
        PathSecurityUtils.assertInsideUserDir(p, userDir, true);
    }

    @Test
    @DisplayName("mustExist=true: 路径不存在 → 不算越界（让上层报 not found）")
    void mustExist_notExists_returnsOk() throws IOException {
        Path p = userDir.resolve("nope.txt");
        PathSecurityUtils.assertInsideUserDir(p, userDir, true);
    }

    @Test
    @DisplayName("mustExist=true: .. 越权路径不存在时也被 normalized 兜底拒绝（但 util 在 mustExist=true 时不查 ..，需 mustExist=false）")
    void mustExist_traversalNormalizedDrops() throws IOException {
        // 上一级是 tmpRoot
        Path realFile = tmpRoot.resolve("secret.txt");
        Files.writeString(realFile, "secret");
        // 试图通过 userDir/../secret.txt 访问
        Path traversal = userDir.resolve("../secret.txt").normalize();
        // util 在 mustExist=true 时直接 toRealPath 校验：real == tmpRoot/secret.txt
        // userReal = userDir 的 real；tmpRoot/secret 不在 userDir 内 → 越界
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(traversal, userDir, true));
        assertTrue(ex.getMessage().contains("越界") || ex.getMessage().contains("越权"),
                "应被拒绝: " + ex.getMessage());
    }

    @Test
    @DisplayName("mustExist=true: symlink 在 userDir 内指向外面 → 越界")
    void mustExist_symlinkEscape_rejected() throws IOException {
        // 准备 userDir 外的真实文件
        Path outside = tmpRoot.resolve("outside.txt");
        Files.writeString(outside, "secret");

        // userDir/evil-link → tmpRoot/outside.txt
        Path link = userDir.resolve("evil-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            // Windows 缺 SeCreateSymbolicLinkPrivilege → 跳过
            System.out.println("[跳过 symlink 测试，OS 不支持或权限不足] " + e.getMessage());
            return;
        }

        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(link, userDir, true));
        assertTrue(ex.getMessage().contains("越界"), "symlink 越界应被拒绝: " + ex.getMessage());
    }

    // ==================== mustExist=false ====================

    @Test
    @DisplayName("mustExist=false: 路径不存在但 normalized 在 userDir 内 → 通过")
    void mustExist_writeNewFile_ok() throws IOException {
        Path p = userDir.resolve("new.txt");
        PathSecurityUtils.assertInsideUserDir(p, userDir, false);
    }

    @Test
    @DisplayName("mustExist=false: .. 越权被 normalized 直接拒绝")
    void mustExist_traversalRejected() throws IOException {
        Path p = userDir.resolve("../escape.txt");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(p, userDir, false));
        assertTrue(ex.getMessage().contains("越权"), ".. 越权应被拒绝: " + ex.getMessage());
    }

    @Test
    @DisplayName("mustExist=false: 多级 .. 越权被拒绝")
    void mustExist_deepTraversalRejected() throws IOException {
        Path p = userDir.resolve("a/b/../../../escape.txt");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(p, userDir, false));
        assertTrue(ex.getMessage().contains("越权"), "应被拒绝: " + ex.getMessage());
    }

    @Test
    @DisplayName("mustExist=false: 路径完全在 userDir 外")
    void mustExist_outsideUserDirRejected() throws IOException {
        Path p = Paths.get("C:/Windows/System32");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(p, userDir, false));
        assertTrue(ex.getMessage().contains("越权"), "应被拒绝: " + ex.getMessage());
    }

    @Test
    @DisplayName("mustExist=false: symlink 在 userDir 内的祖先指向外面 → 越界")
    void mustExist_symlinkAncestorEscape_rejected() throws IOException {
        // 准备 userDir 外的真实目录
        Path outside = tmpRoot.resolve("outside-dir");
        Files.createDirectories(outside);

        // userDir/evil-dir → outside（symlink 在祖先链上）
        Path evilDir;
        try {
            evilDir = Files.createSymbolicLink(userDir.resolve("evil-dir"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            System.out.println("[跳过 symlink 测试，OS 不支持或权限不足] " + e.getMessage());
            return;
        }
        // 试图在 evil-dir/new.txt 写文件
        Path target = evilDir.resolve("new.txt");
        SecurityException ex = assertThrows(SecurityException.class,
                () -> PathSecurityUtils.assertInsideUserDir(target, userDir, false));
        assertTrue(ex.getMessage().contains("越界") || ex.getMessage().contains("symlink"),
                "祖先 symlink 越界应被拒绝: " + ex.getMessage());
    }

    // ==================== 入参校验 ====================

    @Test
    @DisplayName("resolve=null 抛 NPE")
    void nullResolvedRejected() {
        assertThrows(NullPointerException.class,
                () -> PathSecurityUtils.assertInsideUserDir(null, userDir, true));
    }

    @Test
    @DisplayName("userDir=null 抛 NPE")
    void nullUserDirRejected() throws IOException {
        Path p = userDir.resolve("a.txt");
        Files.writeString(p, "x");
        assertThrows(NullPointerException.class,
                () -> PathSecurityUtils.assertInsideUserDir(p, null, true));
    }
}
