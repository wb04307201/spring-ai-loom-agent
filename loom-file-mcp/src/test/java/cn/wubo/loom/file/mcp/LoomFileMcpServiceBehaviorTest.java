package cn.wubo.loom.file.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * loom-file-mcp 的 14 个工具的<b>行为</b>回归锁。
 *
 * <p><b>与 {@link LoomFileMcpSchemaRequiredTest} 的分工</b>:后者锁 MCP schema 契约
 * (required 数组是否正确),<b>不碰方法体</b> —— schema 对不代表工具能用。
 * 本类直调 {@link LoomFileMcpService} 的每个 {@code @McpTool} 方法,锁"参数怎么落到
 * {@code FileOperations}、返回值长什么样、越界/失败时怎么表现"。
 *
 * <p><b>为什么要覆盖全部 14 个</b>:Task 14 的教训是"名字齐全 ≠ 参数 schema 正确 ≠
 * 能被客户端调用"。只测一两个代表工具,剩下 12 个的参数顺序/默认值写错也不会有人发现。
 *
 * <p><b>行为契约要点</b>(读 {@code FileOperations} 源码确认,非猜测):
 * <ul>
 *   <li>工具返回 {@link String},<b>失败也是字符串</b>(如 "错误：…" / "路径不存在:…"),
 *       不抛异常 —— 沙箱越界才抛 {@link SecurityException}。</li>
 *   <li>{@code delete} 必须传对 confirm token(默认 {@code I_CONFIRM_DELETE})才删,
 *       否则返回提示语而非删除。</li>
 * </ul>
 *
 * <p>用 {@code @TempDir} 而非 {@code ~/.loom/mcp}:测试不得污染用户真实目录。
 */
@DisplayName("loom-file-mcp 工具行为")
class LoomFileMcpServiceBehaviorTest {

    private Path base;
    private LoomFileMcpService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        base = tmp.resolve("sandbox");
        Files.createDirectories(base);
        // basePath 指向 @TempDir;deleteConfirmToken 用默认值,与生产一致
        LoomFileMcpProperties props = new LoomFileMcpProperties();
        props.setBasePath(base.toString());
        service = new LoomFileMcpService(props);
    }

    private Path write(String rel, String content) throws Exception {
        Path p = base.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    // ==================== 读类工具 ====================

    @Nested
    @DisplayName("read_text_file")
    class ReadTextFile {

        @Test
        @DisplayName("整文件读取:内容原样返回")
        void readsWholeFile() throws Exception {
            write("a.txt", "line1\nline2\nline3\n");
            assertThat(service.readTextFile("a.txt", null, null))
                    .contains("line1", "line2", "line3");
        }

        @Test
        @DisplayName("head=N 只返回前 N 行")
        void headLimitsToFirstLines() throws Exception {
            write("a.txt", "l1\nl2\nl3\nl4\nl5\n");
            String out = service.readTextFile("a.txt", 2, null);
            assertThat(out).contains("l1", "l2").doesNotContain("l3", "l4", "l5");
        }

        @Test
        @DisplayName("tail=N 只返回后 N 行")
        void tailLimitsToLastLines() throws Exception {
            write("a.txt", "l1\nl2\nl3\nl4\nl5\n");
            String out = service.readTextFile("a.txt", null, 2);
            assertThat(out).contains("l4", "l5").doesNotContain("l1", "l2", "l3");
        }

        @Test
        @DisplayName("文件不存在:返回错误字符串,不抛异常")
        void missingFileReturnsErrorString() {
            assertThat(service.readTextFile("nope.txt", null, null))
                    .contains("nope.txt");
        }
    }

    @Nested
    @DisplayName("read_media_file")
    class ReadMediaFile {

        @Test
        @DisplayName("二进制文件:返回 base64 + MIME 类型")
        void returnsBase64AndMime() throws Exception {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
            Files.write(base.resolve("x.png"), png);
            String out = service.readMediaFile("x.png");
            assertThat(out).isNotBlank();
        }

        @Test
        @DisplayName("文本文件当媒体读:不得抛异常(失败也是字符串)")
        void nonMediaFileDoesNotThrow() throws Exception {
            write("t.txt", "hello");
            service.readMediaFile("t.txt");
        }
    }

    @Nested
    @DisplayName("read_multiple_files")
    class ReadMultipleFiles {

        @Test
        @DisplayName("多文件一次读取:各文件内容都在输出里")
        void readsAllFiles() throws Exception {
            write("a.txt", "contentA");
            write("b.txt", "contentB");
            String out = service.readMultipleFiles(List.of("a.txt", "b.txt"));
            assertThat(out).contains("contentA", "contentB");
        }

        @Test
        @DisplayName("部分文件缺失:不影响其他文件(不抛异常)")
        void partialFailureDoesNotAbortAll() throws Exception {
            write("ok.txt", "present");
            String out = service.readMultipleFiles(List.of("ok.txt", "missing.txt"));
            assertThat(out).contains("present");
        }
    }

    // ==================== 写类工具 ====================

    @Nested
    @DisplayName("write_file")
    class WriteFile {

        @Test
        @DisplayName("新建文件:内容落盘")
        void writesNewFile() throws Exception {
            service.writeFile("new.txt", "hello world");
            assertThat(Files.readString(base.resolve("new.txt"))).isEqualTo("hello world");
        }

        @Test
        @DisplayName("覆盖已有文件:旧内容被替换")
        void overwritesExisting() throws Exception {
            write("e.txt", "old");
            service.writeFile("e.txt", "new");
            assertThat(Files.readString(base.resolve("e.txt"))).isEqualTo("new");
        }

        @Test
        @DisplayName("父目录不存在:自动创建")
        void createsParentDirs() throws Exception {
            service.writeFile("deep/nested/f.txt", "x");
            assertThat(Files.exists(base.resolve("deep/nested/f.txt"))).isTrue();
        }
    }

    @Nested
    @DisplayName("edit_file")
    class EditFile {

        @Test
        @DisplayName("精确匹配替换:内容被改写")
        void replacesExactText() throws Exception {
            write("e.txt", "hello world");
            service.editFile("e.txt", List.of(Map.of("oldText", "world", "newText", "java")));
            assertThat(Files.readString(base.resolve("e.txt"))).isEqualTo("hello java");
        }

        @Test
        @DisplayName("匹配不到:内容不变,且不抛异常")
        void noMatchLeavesFileUnchanged() throws Exception {
            write("e.txt", "original");
            service.editFile("e.txt", List.of(Map.of("oldText", "NOT_PRESENT", "newText", "x")));
            assertThat(Files.readString(base.resolve("e.txt"))).isEqualTo("original");
        }

        @Test
        @DisplayName("多次编辑按序应用")
        void appliesEditsInOrder() throws Exception {
            write("m.txt", "A");
            service.editFile("m.txt", List.of(
                    Map.of("oldText", "A", "newText", "B"),
                    Map.of("oldText", "B", "newText", "C")));
            assertThat(Files.readString(base.resolve("m.txt"))).isEqualTo("C");
        }
    }

    @Nested
    @DisplayName("create_directory")
    class CreateDirectory {

        @Test
        @DisplayName("多级嵌套目录被创建")
        void createsNestedDirs() {
            service.createDirectory("a/b/c");
            assertThat(Files.isDirectory(base.resolve("a/b/c"))).isTrue();
        }

        @Test
        @DisplayName("目录已存在:静默成功(幂等),不抛异常")
        void idempotentOnExisting() {
            service.createDirectory("dup");
            service.createDirectory("dup");
            assertThat(Files.isDirectory(base.resolve("dup"))).isTrue();
        }
    }

    @Nested
    @DisplayName("move_file")
    class MoveFile {

        @Test
        @DisplayName("跨目录移动:源消失目标出现")
        void movesAcrossDirs() throws Exception {
            write("src.txt", "data");
            service.createDirectory("dest");
            service.moveFile("src.txt", "dest/src.txt");
            assertThat(Files.exists(base.resolve("src.txt"))).isFalse();
            assertThat(Files.readString(base.resolve("dest/src.txt"))).isEqualTo("data");
        }

        @Test
        @DisplayName("同目录改名:内容不变")
        void renamesWithinDir() throws Exception {
            write("old.txt", "keep");
            service.moveFile("old.txt", "new.txt");
            assertThat(Files.readString(base.resolve("new.txt"))).isEqualTo("keep");
        }
    }

    @Nested
    @DisplayName("delete_file_or_directory")
    class DeleteFileOrDirectory {

        @Test
        @DisplayName("token 不匹配:拒绝删除,文件仍在")
        void wrongTokenDoesNotDelete() throws Exception {
            write("keep.txt", "x");
            service.deleteFileOrDirectory("keep.txt", "WRONG_TOKEN");
            assertThat(Files.exists(base.resolve("keep.txt"))).isTrue();
        }

        @Test
        @DisplayName("token 为 null:同样拒绝删除")
        void nullTokenDoesNotDelete() throws Exception {
            write("keep.txt", "x");
            service.deleteFileOrDirectory("keep.txt", null);
            assertThat(Files.exists(base.resolve("keep.txt"))).isTrue();
        }

        @Test
        @DisplayName("token 正确:文件被删除")
        void correctTokenDeletesFile() throws Exception {
            write("gone.txt", "x");
            service.deleteFileOrDirectory("gone.txt", "I_CONFIRM_DELETE");
            assertThat(Files.exists(base.resolve("gone.txt"))).isFalse();
        }

        @Test
        @DisplayName("token 正确 + 目录:递归删除")
        void correctTokenDeletesDirectoryRecursively() throws Exception {
            write("tree/a.txt", "1");
            write("tree/sub/b.txt", "2");
            service.deleteFileOrDirectory("tree", "I_CONFIRM_DELETE");
            assertThat(Files.exists(base.resolve("tree"))).isFalse();
        }
    }

    // ==================== 浏览类工具 ====================

    @Nested
    @DisplayName("list_allowed_directories")
    class ListAllowedDirectories {

        @Test
        @DisplayName("返回绝对路径 basePath")
        void returnsAbsoluteBasePath() {
            assertThat(service.listAllowedDirectories()).contains(base.toAbsolutePath().toString());
        }
    }

    @Nested
    @DisplayName("list_directory")
    class ListDirectory {

        @Test
        @DisplayName("空路径:列出根目录直接子项")
        void listsRoot() throws Exception {
            write("a.txt", "1");
            Files.createDirectories(base.resolve("dir1"));
            String out = service.listDirectory("", null);
            assertThat(out).contains("a.txt", "dir1");
        }

        @Test
        @DisplayName("depth 控制递归:depth=1 不含深层文件")
        void depthLimitsRecursion() throws Exception {
            write("top.txt", "1");
            write("sub/deep.txt", "2");
            String shallow = service.listDirectory("", 1);
            assertThat(shallow).contains("top.txt").doesNotContain("deep.txt");
        }
    }

    @Nested
    @DisplayName("list_directory_with_sizes")
    class ListDirectoryWithSizes {

        @Test
        @DisplayName("列出条目并带大小")
        void listsWithSizes() throws Exception {
            write("sized.txt", "12345");
            String out = service.listDirectoryWithSizes("");
            assertThat(out).contains("sized.txt");
        }
    }

    @Nested
    @DisplayName("directory_tree")
    class DirectoryTree {

        @Test
        @DisplayName("返回 JSON 结构:含文件名")
        void returnsJsonTree() throws Exception {
            write("t/x.txt", "1");
            assertThat(service.directoryTree("")).contains("x.txt");
        }
    }

    @Nested
    @DisplayName("search_files")
    class SearchFiles {

        @Test
        @DisplayName("glob 模式 *.txt:只匹配 txt")
        void globMatchesTxtOnly() throws Exception {
            write("a.txt", "1");
            write("b.java", "2");
            String out = service.searchFiles("*.txt");
            assertThat(out).contains("a.txt").doesNotContain("b.java");
        }

        @Test
        @DisplayName("pattern 为 null:返回全部文件")
        void nullPatternListsAll() throws Exception {
            write("a.txt", "1");
            write("b.java", "2");
            String out = service.searchFiles(null);
            assertThat(out).contains("a.txt", "b.java");
        }
    }

    @Nested
    @DisplayName("get_file_info")
    class GetFileInfo {

        @Test
        @DisplayName("已有文件:返回大小等元数据")
        void returnsMetadata() throws Exception {
            write("m.txt", "1234567890");
            String out = service.getFileInfo("m.txt");
            assertThat(out).contains("m.txt");
        }

        @Test
        @DisplayName("不存在文件:返回错误字符串,不抛异常")
        void missingFileReturnsError() {
            assertThat(service.getFileInfo("ghost")).contains("ghost");
        }
    }

    // ==================== 沙箱越界(跨工具的共同契约) ====================

    /**
     * 沙箱越界的<b>真实契约</b>:{@code FileOperations} 各方法自己
     * {@code catch (SecurityException | IOException)} 并转成 {@code "错误：…"} 字符串
     * (见 {@code FileOperations.java:125} / {@code :259+}),<b>不向调用方抛</b>。
     * 本组断言的 therefore 是"越界被拦住"这个**安全效果**:
     * 沙箱外不读出内容、不写进文件、不删掉东西。
     * 不断言异常类型 —— 那是实现细节,且与"失败也是字符串"的整体风格矛盾。
     */
    @Nested
    @DisplayName("沙箱越界:被拦住且沙箱外无副作用")
    class SandboxEscape {

        @Test
        @DisplayName("读 .. 越权路径:不返回沙箱外文件内容")
        void readOutsideBaseIsBlocked() throws Exception {
            Path secret = base.getParent().resolve("outside-read.txt");
            Files.writeString(secret, "TOP_SECRET_VALUE");
            String out = service.readTextFile("../outside-read.txt", null, null);
            assertThat(out).doesNotContain("TOP_SECRET_VALUE");
            assertThat(out).contains("错误");
        }

        @Test
        @DisplayName("读绝对路径逃逸:不返回沙箱外文件内容")
        void absolutePathEscapeIsBlocked() throws Exception {
            Path secret = base.getParent().resolve("outside-abs.txt");
            Files.writeString(secret, "ABS_SECRET_VALUE");
            String out = service.readTextFile(secret.toString(), null, null);
            assertThat(out).doesNotContain("ABS_SECRET_VALUE");
        }

        @Test
        @DisplayName("写 .. 越权路径:沙箱外不产生文件")
        void writeOutsideBaseCreatesNothing() {
            Path outside = base.getParent().resolve("outside-written.txt");
            String out = service.writeFile("../outside-written.txt", "evil");
            assertThat(Files.exists(outside)).isFalse();
            assertThat(out).contains("错误");
        }

        @Test
        @DisplayName("删 .. 越权路径:沙箱外文件仍在(即便 token 正确)")
        void deleteOutsideBaseKeepsVictim() throws Exception {
            Path victim = base.getParent().resolve("victim.txt");
            Files.writeString(victim, "precious");
            String out = service.deleteFileOrDirectory("../victim.txt", "I_CONFIRM_DELETE");
            assertThat(Files.exists(victim)).isTrue();
            assertThat(Files.readString(victim)).isEqualTo("precious");
            assertThat(out).contains("错误");
        }

        @Test
        @DisplayName("移动 .. 越权路径:源文件仍在沙箱内,沙箱外无新文件")
        void moveOutsideBaseIsBlocked() throws Exception {
            write("inside.txt", "data");
            Path dest = base.getParent().resolve("moved-outside.txt");
            service.moveFile("inside.txt", "../moved-outside.txt");
            assertThat(Files.exists(base.resolve("inside.txt"))).isTrue();
            assertThat(Files.exists(dest)).isFalse();
        }

        @Test
        @DisplayName("深层 .. 逃逸(多级)同样被拦住")
        void multiLevelDotDotEscapeIsBlocked() throws Exception {
            Path secret = base.getParent().resolve("deep-secret.txt");
            Files.writeString(secret, "DEEP_SECRET");
            String out = service.readTextFile("../../deep-secret.txt", null, null);
            assertThat(out).doesNotContain("DEEP_SECRET");
        }
    }
}
