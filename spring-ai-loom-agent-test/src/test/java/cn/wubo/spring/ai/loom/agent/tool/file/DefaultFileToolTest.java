package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DefaultFileTool 单元测试
 * <p>
 * 使用临时目录模拟用户文件目录，覆盖：
 * 1. writeFile/readTextFile 正常读写
 * 2. readTextFile head/tail 行数截取
 * 3. readTextFile 路径不存在/不是文件 错误处理
 * 4. readMultipleFiles 批量读取
 * 5. createDirectory / moveFile 目录与移动
 * 6. searchFiles glob 模式匹配
 * 7. listAllowedDirectories / listDirectory / directoryTree
 * 8. getFileInfo 元数据
 * 9. readMediaFile base64 编码
 * 10. 越权拒绝：绝对路径、.. 穿越
 * 11. 不同 username 路径隔离
 * 12. downloadFileUrl/viewFileUrl 路径不存在时返回错误
 */
@DisplayName("DefaultFileTool 单元测试")
class DefaultFileToolTest {

    private DefaultFileTool tool;
    private IFile fileService;
    private Path tmpRoot;
    private String username;

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(IFile.class);
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(null);

        // DefaultFileTool 通过 Paths.get(fileBasePath, username) 拼出用户目录
        // 为了让 username 路径与 tmpRoot 一致，把 fileBasePath 设为 tmpRoot.parent，
        // 并把 username 设为 tmpRoot.fileName。
        tmpRoot = Files.createTempDirectory("loom-filetool-test-");
        username = tmpRoot.getFileName().toString();
        tool = new DefaultFileTool(fileService, tmpRoot.getParent().toString());

        // 准备一个基础目录
        Files.createDirectories(tmpRoot.resolve("notes"));
        Files.writeString(tmpRoot.resolve("notes/hello.txt"), "line1\nline2\nline3\nline4\n", StandardCharsets.UTF_8);
        Files.writeString(tmpRoot.resolve("readme.md"), "# Readme\nBody\n", StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tmpRoot != null && Files.exists(tmpRoot)) {
            Files.walkFileTree(tmpRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
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
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("username", username);
        return new ToolContext(ctx);
    }

    // ==================== 读写 ====================

    @Test
    @DisplayName("writeFile 创建文件并写入内容")
    void writeFile_createsFile() {
        String result = tool.writeFile("new.txt", "hello world", ctx(username));
        assertTrue(result.contains("已写入"), "应提示已写入: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("new.txt")));
        try {
            assertEquals("hello world", Files.readString(tmpRoot.resolve("new.txt")));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("writeFile 自动创建父目录")
    void writeFile_createsParentDirs() {
        String result = tool.writeFile("a/b/c/note.txt", "x", ctx(username));
        assertTrue(result.contains("已写入"), result);
        assertTrue(Files.exists(tmpRoot.resolve("a/b/c/note.txt")));
    }

    @Test
    @DisplayName("readTextFile 读取完整内容")
    void readTextFile_readsContent() {
        String result = tool.readTextFile("notes/hello.txt", null, null, ctx(username));
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line4"));
    }

    @Test
    @DisplayName("readTextFile head 截取前 N 行")
    void readTextFile_headLimitsLines() {
        String result = tool.readTextFile("notes/hello.txt", 2, null, ctx(username));
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line2"));
        assertFalse(result.contains("line3"), "head=2 不应包含 line3: " + result);
    }

    @Test
    @DisplayName("readTextFile tail 截取后 N 行")
    void readTextFile_tailLimitsLines() {
        String result = tool.readTextFile("notes/hello.txt", null, 2, ctx(username));
        assertTrue(result.contains("line3"));
        assertTrue(result.contains("line4"));
        assertFalse(result.contains("line1"), "tail=2 不应包含 line1: " + result);
    }

    @Test
    @DisplayName("readTextFile 文件不存在返回错误")
    void readTextFile_notFound() {
        String result = tool.readTextFile("nope.txt", null, null, ctx(username));
        assertTrue(result.contains("文件不存在"), "应提示文件不存在: " + result);
    }

    @Test
    @DisplayName("readMultipleFiles 批量读取，单个失败不影响其他")
    void readMultipleFiles_mixedResults() {
        String result = tool.readMultipleFiles(List.of("notes/hello.txt", "nope.txt"), ctx(username));
        assertTrue(result.contains("line1"), "应包含 hello.txt 内容");
        assertTrue(result.contains("nope.txt"), "应提示 nope.txt 错误");
        assertTrue(result.contains("文件不存在"), "应给出错误信息");
    }

    // ==================== 目录与移动 ====================

    @Test
    @DisplayName("createDirectory 创建多级目录")
    void createDirectory_createsNested() {
        String result = tool.createDirectory("a/b/c", ctx(username));
        assertTrue(result.contains("已创建") || result.contains("已存在"), result);
        assertTrue(Files.isDirectory(tmpRoot.resolve("a/b/c")));
    }

    @Test
    @DisplayName("createDirectory 已存在目录静默成功")
    void createDirectory_existingDirIsIdempotent() {
        String result = tool.createDirectory("notes", ctx(username));
        assertTrue(result.contains("已存在"), "已存在目录应提示: " + result);
    }

    @Test
    @DisplayName("moveFile 跨目录移动")
    void moveFile_movesAcrossDirs() {
        String result = tool.moveFile("readme.md", "notes/readme.md", ctx(username));
        assertTrue(result.contains("已移动"), result);
        assertFalse(Files.exists(tmpRoot.resolve("readme.md")));
        assertTrue(Files.exists(tmpRoot.resolve("notes/readme.md")));
    }

    // ==================== 搜索/列表/信息 ====================

    @Test
    @DisplayName("searchFiles glob 模式匹配")
    void searchFiles_globPattern() {
        String result = tool.searchFiles("*.md", ctx(username));
        assertTrue(result.contains("readme.md"), "应找到 readme.md: " + result);
        assertFalse(result.contains("hello.txt"), "不应匹配 .txt: " + result);
    }

    @Test
    @DisplayName("searchFiles 无匹配返回提示")
    void searchFiles_noMatch() {
        String result = tool.searchFiles("*.xyz", ctx(username));
        assertEquals("未找到匹配的文件", result);
    }

    @Test
    @DisplayName("listAllowedDirectories 返回用户目录")
    void listAllowedDirectories_returnsUserDir() {
        String result = tool.listAllowedDirectories(ctx(username));
        assertTrue(result.contains(tmpRoot.toString()), "应返回用户目录路径: " + result);
        assertTrue(result.contains("用户文件目录"), result);
    }

    @Test
    @DisplayName("listDirectory 列出根目录")
    void listDirectory_root() {
        String result = tool.listDirectory("", null, ctx(username));
        assertTrue(result.contains("[DIR]") && result.contains("notes"), result);
        assertTrue(result.contains("[FILE]") && result.contains("readme.md"), result);
    }

    @Test
    @DisplayName("listDirectory 目录不存在返回错误")
    void listDirectory_notFound() {
        String result = tool.listDirectory("no-such-dir", null, ctx(username));
        assertTrue(result.contains("目录不存在"), result);
    }

    @Test
    @DisplayName("directoryTree 返回 JSON 树")
    void directoryTree_returnsJson() {
        String result = tool.directoryTree("", ctx(username));
        assertTrue(result.startsWith("{"), "应返回 JSON 对象: " + result);
        assertTrue(result.contains("\"name\""), "应包含 name 字段: " + result);
        assertTrue(result.contains("notes"), "应包含子目录: " + result);
    }

    @Test
    @DisplayName("getFileInfo 返回元数据")
    void getFileInfo_returnsMetadata() {
        String result = tool.getFileInfo("notes/hello.txt", ctx(username));
        assertTrue(result.contains("名称"), "应包含名称: " + result);
        assertTrue(result.contains("类型: 文件"), "应标记为文件: " + result);
        assertTrue(result.contains("行数: 4"), "hello.txt 应有 4 行: " + result);
    }

    @Test
    @DisplayName("getFileInfo 路径不存在返回错误")
    void getFileInfo_notFound() {
        String result = tool.getFileInfo("missing.txt", ctx(username));
        assertTrue(result.contains("不存在"), result);
    }

    // ==================== 媒体 ====================

    @Test
    @DisplayName("readMediaFile 返回 base64 编码")
    void readMediaFile_returnsBase64() {
        // 写入 3 字节已知内容
        try {
            Files.write(tmpRoot.resolve("tiny.bin"), new byte[]{1, 2, 3});
        } catch (IOException e) {
            fail(e);
        }
        String result = tool.readMediaFile("tiny.bin", ctx(username));
        assertTrue(result.startsWith("MIME类型："), "应输出 MIME 类型头: " + result);
        assertTrue(result.contains("Base64数据"), "应包含 base64 数据: " + result);
        assertTrue(result.contains("AQID"), "1,2,3 的 base64 编码应为 AQID: " + result);
    }

    // ==================== 越权与隔离 ====================

    @Test
    @DisplayName("readTextFile 绝对路径越权抛出 SecurityException")
    void readTextFile_absolutePathRejected() {
        // 绝对路径在用户目录外，会被 resolvePath 拒绝（向上抛 SecurityException）
        SecurityException ex = assertThrows(SecurityException.class,
                () -> tool.readTextFile("/etc/passwd", null, null, ctx(username)));
        assertTrue(ex.getMessage().contains("路径不能超出"), "异常信息应说明越权: " + ex.getMessage());
    }

    @Test
    @DisplayName("readTextFile .. 穿越越权抛出 SecurityException")
    void readTextFile_traversalRejected() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> tool.readTextFile("../escape.txt", null, null, ctx(username)));
        assertTrue(ex.getMessage().contains("路径不能超出"), "异常信息应说明越权: " + ex.getMessage());
    }

    @Test
    @DisplayName("writeFile .. 穿越越权抛出 SecurityException")
    void writeFile_traversalRejected() {
        SecurityException ex = assertThrows(SecurityException.class,
                () -> tool.writeFile("../escape.txt", "x", ctx(username)));
        assertTrue(ex.getMessage().contains("路径不能超出"), "异常信息应说明越权: " + ex.getMessage());
    }

    @Test
    @DisplayName("不同 username 路径相互隔离")
    void usernamesAreIsolated() {
        // username=bob 看不到 alice 的文件
        String result = tool.readTextFile("readme.md", null, null, ctx("bob"));
        // bob 的目录不存在，所以该路径解析后不存在
        assertTrue(result.contains("文件不存在"), "bob 不应看到 alice 的文件: " + result);
    }

    // ==================== Preview / Download ====================

    @Test
    @DisplayName("downloadFileUrl 文件不存在返回错误")
    void downloadFileUrl_notFound() {
        String result = tool.downloadFileUrl("missing.txt", ctx(username));
        assertTrue(result.contains("文件不存在"), result);
    }

    @Test
    @DisplayName("viewFileUrl 文件不存在返回错误")
    void viewFileUrl_notFound() {
        String result = tool.viewFileUrl("missing.txt", ctx(username));
        assertTrue(result.contains("文件不存在"), result);
    }
}
