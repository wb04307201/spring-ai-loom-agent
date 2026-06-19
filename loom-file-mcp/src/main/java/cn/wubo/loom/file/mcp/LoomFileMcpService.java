package cn.wubo.loom.file.mcp;

import cn.wubo.loom.file.core.FileOperations;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Loom File MCP 服务端。
 * <p>
 * 将文件操作暴露为 Spring AI {@link Tool} 方法，供 AI 客户端调用。
 * basePath 从配置中读取，所有文件操作都在此目录下进行。
 * </p>
 */
public class LoomFileMcpService {

    private final FileOperations fileOps;
    private final Path basePath;

    public LoomFileMcpService(LoomFileMcpProperties props) {
        this.basePath = Paths.get(props.getBasePath()).toAbsolutePath().normalize();
        this.fileOps = new FileOperations(new FileOperations.Config(
                props.getMaxFileSize(),
                props.getMaxMediaSize(),
                props.getMaxWalkDepth(),
                props.getMaxWalkEntries(),
                props.getMaxSearchResults(),
                props.getExcludedDirs(),
                props.getDeleteConfirmToken()
        ));
    }

    // ==================== Read operations ====================

    @Tool(description = "读取本地文件内容为文本。path 为相对于基础目录的路径。支持 head（仅前N行）或 tail（仅后N行）参数。")
    public String readTextFile(
            @ToolParam(description = "相对于基础目录的文件路径") String path,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail) {
        return fileOps.readText(basePath, path, head, tail);
    }

    @Tool(description = "读取本地图片或音频文件，返回 base64 编码数据和 MIME 类型。")
    public String readMediaFile(
            @ToolParam(description = "相对于基础目录的文件路径") String path) {
        return fileOps.readMedia(basePath, path);
    }

    @Tool(description = "同时读取多个文件内容，比逐个读取更高效。单个文件读取失败不会影响其他文件。")
    public String readMultipleFiles(
            @ToolParam(description = "要读取的文件路径列表（相对于基础目录）") List<String> paths) {
        return fileOps.readMultiple(basePath, paths);
    }

    // ==================== Write / Edit operations ====================

    @Tool(description = "创建新文件或完全覆盖已有文件内容。path 为相对于基础目录的路径。如果文件已存在则覆盖内容。")
    public String writeFile(
            @ToolParam(description = "相对于基础目录的文件路径") String path,
            @ToolParam(description = "要写入的文本内容") String content) {
        return fileOps.write(basePath, path, content);
    }

    @Tool(description = "对本地文件进行基于行的编辑，每次编辑用新内容替换精确匹配的文本序列，返回 git 风格的 diff。")
    public String editFile(
            @ToolParam(description = "相对于基础目录的文件路径") String path,
            @ToolParam(description = "编辑列表，每个编辑包含 oldText（要替换的文本）和 newText（替换后的文本）") List<Map<String, String>> edits) {
        return fileOps.edit(basePath, path, edits);
    }

    // ==================== Directory operations ====================

    @Tool(description = "创建新目录或确保目录已存在，支持创建多级嵌套目录，如果目录已存在则静默成功。")
    public String createDirectory(
            @ToolParam(description = "相对于基础目录的目录路径") String path) {
        return fileOps.createDirectory(basePath, path);
    }

    // ==================== Move operation ====================

    @Tool(description = "移动或重命名本地文件。可以跨目录移动或仅重命名。")
    public String moveFile(
            @ToolParam(description = "源文件路径（相对于基础目录）") String source,
            @ToolParam(description = "目标路径（相对于基础目录）") String destination) {
        return fileOps.moveFile(basePath, source, destination);
    }

    // ==================== Search ====================

    @Tool(description = "在基础目录中递归搜索文件。pattern 为 glob 风格，如 '*.txt' 或 '**/*.java'。不传则返回所有文件。")
    public String searchFiles(
            @ToolParam(description = "搜索模式（glob风格），如 '*.txt' 或 '**/*.java'。不传则返回所有文件", required = false) String pattern) {
        return fileOps.searchFiles(basePath, pattern);
    }

    // ==================== List directories ====================

    @Tool(description = "列出文件操作的基础目录（返回的是**绝对路径**）。写入或创建文件时，path 参数均相对于此目录。")
    public String listAllowedDirectories() {
        return fileOps.listBaseDirectory(basePath);
    }

    @Tool(description = "列出目录内容。区分文件 [FILE] 和目录 [DIR]。支持 depth 参数控制递归深度。")
    public String listDirectory(
            @ToolParam(description = "相对于基础目录的目录路径，空字符串列出根目录") String path,
            @ToolParam(description = "递归深度（默认1，仅直接子项）", required = false) Integer depth) {
        return fileOps.listDirectory(basePath, path, depth);
    }

    @Tool(description = "列出目录内容及每个项目的大小。区分文件 [FILE] 和目录 [DIR]。")
    public String listDirectoryWithSizes(
            @ToolParam(description = "相对于基础目录的目录路径，空字符串列出根目录") String path) {
        return fileOps.listDirectoryWithSizes(basePath, path);
    }

    @Tool(description = "获取目录的递归树视图，返回 JSON 格式结构。")
    public String directoryTree(
            @ToolParam(description = "相对于基础目录的目录路径，空字符串从根目录开始") String path) {
        return fileOps.directoryTree(basePath, path);
    }

    @Tool(description = "获取文件或目录的详细元数据，包括大小、创建时间、修改时间、权限等。")
    public String getFileInfo(
            @ToolParam(description = "相对于基础目录的文件或目录路径") String path) {
        return fileOps.getFileInfo(basePath, path);
    }

    // ==================== Delete ====================

    @Tool(description = "删除本地文件或目录（递归删除目录与其内容）。必须显式确认（传入 deleteConfirmToken）才能执行。")
    public String deleteFileOrDirectory(
            @ToolParam(description = "相对于基础目录的路径，可以是文件或目录") String path,
            @ToolParam(description = "确认字符串：必须与配置的 deleteConfirmToken 一致", required = false) String confirm) {
        return fileOps.delete(basePath, path, confirm);
    }

}
