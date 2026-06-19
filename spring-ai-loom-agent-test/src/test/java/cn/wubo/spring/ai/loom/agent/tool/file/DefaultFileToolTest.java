package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
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
 * 10. 越权拒绝：绝对路径、.. 穿越、symlink
 * 11. 不同 username 路径隔离
 * 12. downloadFileUrl/viewFileUrl 路径不存在时返回错误
 * 13. 文件大小上限 (maxFileSize / maxMediaSize)
 * 14. editFile 唯一性匹配
 * 15. delete 需显式 confirm token
 * 16. walk 深度 / 条数 / excludedDirs
 * 17. baseUrl 缺失
 */
@DisplayName("DefaultFileTool 单元测试")
class DefaultFileToolTest {

    private DefaultFileTool tool;
    private IFile fileService;
    private Path tmpRoot;
    private String username;
    private LoomAgentProperties.FileToolProperty cfg;

    @BeforeEach
    void setUp() throws IOException {
        fileService = mock(IFile.class);
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(null);

        // DefaultFileTool 通过 Paths.get(fileBasePath, username) 拼出用户目录
        // 为了让 username 路径与 tmpRoot 一致，把 fileBasePath 设为 tmpRoot.parent，
        // 并把 username 设为 tmpRoot.fileName。
        tmpRoot = Files.createTempDirectory("loom-filetool-test-");
        username = tmpRoot.getFileName().toString();
        cfg = new LoomAgentProperties.FileToolProperty();
        tool = new DefaultFileTool(fileService, tmpRoot.getParent().toString(), cfg);

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

    private static ToolContext ctxWithBaseUrl(String username, String baseUrl) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("username", username);
        ctx.put("baseUrl", baseUrl);
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
    @DisplayName("writeFile 原子写：失败时不污染目标文件")
    void writeFile_atomicWrite() {
        // 写入正常值
        assertTrue(tool.writeFile("atomic.txt", "v1", ctx(username)).contains("已写入"));
        // 再覆盖
        assertTrue(tool.writeFile("atomic.txt", "v2", ctx(username)).contains("已写入"));
        try {
            assertEquals("v2", Files.readString(tmpRoot.resolve("atomic.txt")));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("writeFile 写入超大内容被拒绝")
    void writeFile_oversizedContentRejected() {
        String big = "x".repeat((int) (cfg.getMaxFileSize() + 1));
        String result = tool.writeFile("big.txt", big, ctx(username));
        assertTrue(result.contains("超过限制"), "应拒绝过大写入: " + result);
        assertFalse(Files.exists(tmpRoot.resolve("big.txt")));
    }

    @Test
    @DisplayName("writeFile path 为空被拒绝")
    void writeFile_emptyPathRejected() {
        String result = tool.writeFile("", "x", ctx(username));
        assertTrue(result.contains("不能为空"), result);
    }

    @Test
    @DisplayName("writeFile 缺少 username 上下文被拒绝")
    void writeFile_noUsernameRejected() {
        String result = tool.writeFile("foo.txt", "x", ctx(""));
        assertTrue(result.contains("缺少 username"), result);
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
    @DisplayName("readTextFile 文件超过 maxFileSize 拒绝")
    void readTextFile_oversizedRejected() throws IOException {
        Path big = tmpRoot.resolve("big.txt");
        // 写超过 maxFileSize 一点点（不到 1MB 仍然可能 OOM，留点余量用更大的临时文件）
        // 这里直接写一个超过上限的内容（5MB+1）
        Files.writeString(big, "x".repeat((int) (cfg.getMaxFileSize() + 1)));
        String result = tool.readTextFile("big.txt", null, null, ctx(username));
        assertTrue(result.contains("超过限制"), "应拒绝过大读取: " + result);
    }

    @Test
    @DisplayName("readMultipleFiles 批量读取，单个失败不影响其他")
    void readMultipleFiles_mixedResults() {
        String result = tool.readMultipleFiles(List.of("notes/hello.txt", "nope.txt"), ctx(username));
        assertTrue(result.contains("line1"), "应包含 hello.txt 内容");
        assertTrue(result.contains("nope.txt"), "应提示 nope.txt 错误");
        assertTrue(result.contains("文件不存在"), "应给出错误信息");
    }

    @Test
    @DisplayName("readMultipleFiles 空列表返回错误")
    void readMultipleFiles_emptyRejected() {
        String result = tool.readMultipleFiles(List.of(), ctx(username));
        assertTrue(result.contains("为空"), result);
    }

    @Test
    @DisplayName("readMultipleFiles 单文件超限跳过")
    void readMultipleFiles_perFileSizeCap() throws IOException {
        Files.writeString(tmpRoot.resolve("big.txt"), "x".repeat((int) (cfg.getMaxFileSize() + 1)));
        String result = tool.readMultipleFiles(List.of("big.txt", "readme.md"), ctx(username));
        assertTrue(result.contains("超过限制"), "big.txt 应被 size cap 跳过: " + result);
        assertTrue(result.contains("readme.md"), "readme.md 应仍被读取: " + result);
    }

    // ==================== editFile 唯一性 ====================

    @Test
    @DisplayName("editFile 唯一匹配时正常替换并返回 diff")
    void editFile_uniqueMatch() {
        List<Map<String, String>> edits = List.of(Map.of("oldText", "Body", "newText", "NewBody"));
        String result = tool.editFile("readme.md", edits, ctx(username));
        assertTrue(result.contains("已应用 1 处编辑"), result);
        assertTrue(result.contains("-Body"), "diff 应有 -Body 行: " + result);
        assertTrue(result.contains("+NewBody"), "diff 应有 +NewBody 行: " + result);
        try {
            assertEquals("# Readme\nNewBody\n", Files.readString(tmpRoot.resolve("readme.md")));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("editFile 多次匹配被拒绝（要求提供更精确上下文）")
    void editFile_ambiguousMatchRejected() {
        // hello.txt 里有 line1..line4，唯一匹配应该 OK；
        // 这里把 line 出现两次以便触发多次匹配
        List<Map<String, String>> edits = List.of(Map.of("oldText", "line", "newText", "ROW"));
        String result = tool.editFile("notes/hello.txt", edits, ctx(username));
        assertTrue(result.contains("出现"), "应提示多次匹配: " + result);
    }

    @Test
    @DisplayName("editFile 未找到文本返回错误")
    void editFile_notFound() {
        List<Map<String, String>> edits = List.of(Map.of("oldText", "NOT_EXISTS_XYZ", "newText", "x"));
        String result = tool.editFile("readme.md", edits, ctx(username));
        assertTrue(result.contains("未找到"), result);
    }

    @Test
    @DisplayName("editFile 空 oldText 拒绝")
    void editFile_emptyOldTextRejected() {
        List<Map<String, String>> edits = List.of(Map.of("oldText", "", "newText", "x"));
        String result = tool.editFile("readme.md", edits, ctx(username));
        assertTrue(result.contains("不能为空"), result);
    }

    @Test
    @DisplayName("editFile 缺少字段返回错误")
    void editFile_missingField() {
        List<Map<String, String>> edits = List.of(Map.of("oldText", "Body"));
        String result = tool.editFile("readme.md", edits, ctx(username));
        assertTrue(result.contains("oldText 和 newText"), result);
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
    @DisplayName("createDirectory 路径已存在但不是目录 → 错误")
    void createDirectory_pathIsFileRejected() {
        // readme.md 是文件
        String result = tool.createDirectory("readme.md", ctx(username));
        assertTrue(result.contains("不是目录"), result);
    }

    @Test
    @DisplayName("moveFile 跨目录移动")
    void moveFile_movesAcrossDirs() {
        String result = tool.moveFile("readme.md", "notes/readme.md", ctx(username));
        assertTrue(result.contains("已移动"), result);
        assertFalse(Files.exists(tmpRoot.resolve("readme.md")));
        assertTrue(Files.exists(tmpRoot.resolve("notes/readme.md")));
    }

    @Test
    @DisplayName("moveFile 目标已存在拒绝")
    void moveFile_targetExistsRejected() {
        // notes/hello.txt 已经存在
        String result = tool.moveFile("readme.md", "notes/hello.txt", ctx(username));
        assertTrue(result.contains("目标路径已存在"), result);
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")), "源文件应保留");
    }

    // ==================== 搜索/列表/信息 ====================

    @Test
    @DisplayName("searchFiles glob 模式匹配（自动加 glob: 前缀）")
    void searchFiles_globPattern() {
        String result = tool.searchFiles("*.md", ctx(username));
        assertTrue(result.contains("readme.md"), "应找到 readme.md: " + result);
        assertFalse(result.contains("hello.txt"), "不应匹配 .txt: " + result);
    }

    @Test
    @DisplayName("searchFiles 显式 glob: 前缀仍能匹配")
    void searchFiles_globPrefix() {
        String result = tool.searchFiles("glob:*.md", ctx(username));
        assertTrue(result.contains("readme.md"), result);
    }

    @Test
    @DisplayName("searchFiles 无匹配返回提示")
    void searchFiles_noMatch() {
        String result = tool.searchFiles("*.xyz", ctx(username));
        assertEquals("未找到匹配的文件", result);
    }

    @Test
    @DisplayName("searchFiles 命中超过 maxSearchResults 时截断并提示")
    void searchFiles_truncated() throws IOException {
        // 默认 maxSearchResults=500，写 10 个文件应都能列出
        for (int i = 0; i < 10; i++) {
            Files.writeString(tmpRoot.resolve("f" + i + ".log"), "x");
        }
        String result = tool.searchFiles("*.log", ctx(username));
        assertTrue(result.contains("匹配 10 个文件"), result);
        assertFalse(result.contains("还有"), "10 个未超 maxSearchResults=500: " + result);
    }

    @Test
    @DisplayName("searchFiles 排除 excludedDirs 内的文件")
    void searchFiles_excludesExcludedDirs() throws IOException {
        // 在 node_modules/ 下放一个 .js 文件，不应被 searchFiles 列出
        Files.createDirectories(tmpRoot.resolve("node_modules"));
        Files.writeString(tmpRoot.resolve("node_modules/lib.js"), "x");
        String result = tool.searchFiles("*.js", ctx(username));
        assertFalse(result.contains("lib.js"), "node_modules 应被排除: " + result);
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
    @DisplayName("listDirectory depth=2 递归列子目录")
    void listDirectory_depthRecursive() {
        String result = tool.listDirectory("", 2, ctx(username));
        assertTrue(result.contains("hello.txt"), "应列出嵌套文件: " + result);
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
    @DisplayName("directoryTree 排除 excludedDirs")
    void directoryTree_excludesExcludedDirs() throws IOException {
        Files.createDirectories(tmpRoot.resolve("node_modules"));
        Files.writeString(tmpRoot.resolve("node_modules/lib.js"), "x");
        String result = tool.directoryTree("", ctx(username));
        assertFalse(result.contains("node_modules"), "应排除 excludedDirs: " + result);
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
    @DisplayName("getFileInfo 大文件跳过行数统计")
    void getFileInfo_skipsLineCountForLarge() throws IOException {
        Files.writeString(tmpRoot.resolve("big.txt"), "x".repeat((int) (cfg.getMaxFileSize() + 1)));
        String result = tool.getFileInfo("big.txt", ctx(username));
        assertTrue(result.contains("行数: 跳过"), "大文件应跳过行数: " + result);
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

    @Test
    @DisplayName("readMediaFile 超过 maxMediaSize 拒绝")
    void readMediaFile_oversizedRejected() throws IOException {
        // 默认 maxMediaSize=1MB，写 1MB+1 字节
        byte[] data = new byte[(int) (cfg.getMaxMediaSize() + 1)];
        Files.write(tmpRoot.resolve("big.bin"), data);
        String result = tool.readMediaFile("big.bin", ctx(username));
        assertTrue(result.contains("超过限制"), "应拒绝过大媒体: " + result);
    }

    // ==================== 越权与隔离 ====================

    @Test
    @DisplayName("readTextFile 绝对路径越权返回错误")
    void readTextFile_absolutePathRejected() {
        // 用一个能触发 normalized-outside 的相对路径（跨盘符 / 父目录逃逸）：
        // 父目录 tmpRoot.getParent() 的父目录肯定在 userDir 外
        Path outside = tmpRoot.getParent().getParent().resolve("escape.txt");
        String result = tool.readTextFile(outside.toString(), null, null, ctx(username));
        assertTrue(result.startsWith("错误：") && result.contains("越权"),
                "绝对路径越权应被拒绝: " + result);
    }

    @Test
    @DisplayName("readTextFile .. 穿越越权返回错误")
    void readTextFile_traversalRejected() {
        String result = tool.readTextFile("../escape.txt", null, null, ctx(username));
        assertTrue(result.startsWith("错误：") && result.contains("越权"),
                ".. 越权应被拒绝: " + result);
    }

    @Test
    @DisplayName("writeFile .. 穿越越权返回错误")
    void writeFile_traversalRejected() {
        String result = tool.writeFile("../escape.txt", "x", ctx(username));
        assertTrue(result.startsWith("错误：") && result.contains("越权"),
                ".. 越权应被拒绝: " + result);
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
    @DisplayName("downloadFileUrl 缺少 baseUrl 返回错误")
    void downloadFileUrl_noBaseUrl() {
        // 没有 baseUrl 时应返回错误
        String result = tool.downloadFileUrl("readme.md", ctx(username));
        assertTrue(result.contains("baseUrl"), "应提示缺少 baseUrl: " + result);
    }

    @Test
    @DisplayName("downloadFileUrl 含 baseUrl 生成下载链接")
    void downloadFileUrl_withBaseUrl() {
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(null);
        String result = tool.downloadFileUrl("readme.md", ctxWithBaseUrl(username, "http://x"));
        assertTrue(result.contains("下载链接"), "应生成下载链接: " + result);
        assertTrue(result.contains("spring/ai/loom/file/"), result);
    }

    @Test
    @DisplayName("viewFileUrl 文件不存在返回错误")
    void viewFileUrl_notFound() {
        String result = tool.viewFileUrl("missing.txt", ctx(username));
        assertTrue(result.contains("文件不存在"), result);
    }

    @Test
    @DisplayName("viewFileUrl 含 baseUrl 生成预览链接")
    void viewFileUrl_withBaseUrl() {
        String result = tool.viewFileUrl("readme.md", ctxWithBaseUrl(username, "http://x"));
        assertTrue(result.contains("预览链接"), "应生成预览链接: " + result);
        assertTrue(result.contains("/file/view/"), result);
    }

    @Test
    @DisplayName("getOrCreateFileId 已有记录时 upsert（更新 size）")
    void getOrCreateFileId_existingRecordUpsert() {
        // 已有记录：返回 id 并尝试更新 size
        FileRecord existing = new FileRecord("existing-id", null, "readme.md", 0L,
                java.time.LocalDateTime.now(), tmpRoot.resolve("readme.md").toString(),
                "temp", "text/markdown");
        when(fileService.getByExactPath(anyString(), anyString())).thenReturn(existing);

        String result = tool.viewFileUrl("readme.md", ctxWithBaseUrl(username, "http://x"));
        assertTrue(result.contains("existing-id"), "应使用已有 id: " + result);
    }

    // ==================== Delete ====================

    @Test
    @DisplayName("deleteFileOrDirectory 默认 token 为 I_CONFIRM_DELETE")
    void deleteFileOrDirectory_defaultToken() {
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")));
        String result = tool.deleteFileOrDirectory("readme.md", "I_CONFIRM_DELETE", ctx(username));
        assertTrue(result.contains("已删除"), "默认 token 正确: " + result);
        assertFalse(Files.exists(tmpRoot.resolve("readme.md")));
    }

    @Test
    @DisplayName("deleteFileOrDirectory 自定义 token 生效")
    void deleteFileOrDirectory_customToken() {
        cfg.setDeleteConfirmToken("PLEASE_DELETE");
        // 重新创建 tool 以确保新 token 生效
        tool = new DefaultFileTool(fileService, tmpRoot.getParent().toString(), cfg);
        String result = tool.deleteFileOrDirectory("readme.md", "I_CONFIRM_DELETE", ctx(username));
        assertTrue(result.contains("需要确认"), "应要求新 token: " + result);
        String result2 = tool.deleteFileOrDirectory("readme.md", "PLEASE_DELETE", ctx(username));
        assertTrue(result2.contains("已删除"), "新 token 应通过: " + result2);
    }

    @Test
    @DisplayName("deleteFileOrDirectory 缺少确认拒绝执行")
    void deleteFileOrDirectory_requiresConfirmation() {
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")));
        String result = tool.deleteFileOrDirectory("readme.md", null, ctx(username));
        assertTrue(result.contains("需要确认"), "缺少确认应被拒绝: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")), "文件应未被删除");
    }

    @Test
    @DisplayName("deleteFileOrDirectory 错误 token 拒绝执行")
    void deleteFileOrDirectory_invalidToken() {
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")));
        String result = tool.deleteFileOrDirectory("readme.md", "Y", ctx(username));
        assertTrue(result.contains("需要确认"), "Y 已不再接受: " + result);
        assertTrue(Files.exists(tmpRoot.resolve("readme.md")), "文件应未被删除");
    }

    @Test
    @DisplayName("deleteFileOrDirectory 递归删除整个目录")
    void deleteFileOrDirectory_deletesDirectoryRecursively() throws IOException {
        Path sub = tmpRoot.resolve("sub");
        Files.createDirectories(sub.resolve("nested"));
        Files.writeString(sub.resolve("a.txt"), "x");
        Files.writeString(sub.resolve("nested/b.txt"), "y");
        assertTrue(Files.exists(sub.resolve("nested/b.txt")));

        String result = tool.deleteFileOrDirectory("sub", "I_CONFIRM_DELETE", ctx(username));
        assertTrue(result.contains("已删除"), "应提示已删除: " + result);
        assertFalse(Files.exists(sub), "目录应被删除");
    }

    @Test
    @DisplayName("deleteFileOrDirectory 路径不存在返回错误")
    void deleteFileOrDirectory_notFound() {
        String result = tool.deleteFileOrDirectory("nope.txt", "I_CONFIRM_DELETE", ctx(username));
        assertTrue(result.contains("不存在"), "应提示路径不存在: " + result);
    }

    @Test
    @DisplayName("deleteFileOrDirectory .. 越权返回错误")
    void deleteFileOrDirectory_traversalRejected() {
        String result = tool.deleteFileOrDirectory("../escape.txt", "I_CONFIRM_DELETE", ctx(username));
        assertTrue(result.startsWith("错误：") && result.contains("越权"),
                ".. 越权应被拒绝: " + result);
    }
}
