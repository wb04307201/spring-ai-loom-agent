package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.loom.file.core.FileOperations;
import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.util.TikaUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件工具默认实现。
 * <p>
 * 核心文件操作委托给 {@link FileOperations}，本类负责：
 * <ul>
 * <li>从 ToolContext 获取 username</li>
 * <li>拼接 basePath = fileBasePath + username</li>
 * <li>预览/下载链接生成（依赖 IFile 数据库操作）</li>
 * <li>删除后清理 file_info 表中的临时记录</li>
 * </ul>
 */
public class DefaultFileTool implements IFileTool {

    private final IFile file;
    private final String fileBasePath;
    private final LoomAgentProperties.FileToolProperty cfg;
    private final FileOperations fileOps;

    public DefaultFileTool(IFile file, String fileBasePath, LoomAgentProperties.FileToolProperty cfg) {
        this.file = file;
        this.fileBasePath = fileBasePath;
        this.cfg = cfg;
        this.fileOps = new FileOperations(new FileOperations.Config(
                cfg.getMaxFileSize(),
                cfg.getMaxMediaSize(),
                cfg.getMaxWalkDepth(),
                cfg.getMaxWalkEntries(),
                cfg.getMaxSearchResults(),
                new java.util.HashSet<>(cfg.getExcludedDirs()),
                cfg.getDeleteConfirmToken()
        ));
    }

    // ==================== Read operations ====================

    @Tool(description = "读取本地文件内容为文本。path 为相对于用户文件目录的路径。支持 head（仅前N行）或 tail（仅后N行）参数。")
    @Override
    public String readTextFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.readText(basePath, path, head, tail);
    }

    @Tool(description = "读取本地图片或音频文件，返回 base64 编码数据和 MIME 类型。")
    @Override
    public String readMediaFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.readMedia(basePath, path);
    }

    @Tool(description = "同时读取多个文件内容，比逐个读取更高效。单个文件读取失败不会影响其他文件。")
    @Override
    public String readMultipleFiles(
            @ToolParam(description = "要读取的文件路径列表（相对于用户文件目录）") List<String> paths,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.readMultiple(basePath, paths);
    }

    // ==================== Write / Edit operations ====================

    @Tool(description = "创建新文件或完全覆盖已有文件内容。path 为相对于用户文件目录的路径。如果文件已存在则覆盖内容。")
    @Override
    public String writeFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "要写入的文本内容") String content,
            ToolContext toolContext) {
        // 先校验 path，再校验 username（与原始代码保持一致）
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.write(basePath, path, content);
    }

    @Tool(description = "对本地文件进行基于行的编辑，每次编辑用新内容替换精确匹配的文本序列，返回 git 风格的 diff。")
    @Override
    public String editFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "编辑列表，每个编辑包含 oldText（要替换的文本）和 newText（替换后的文本）") List<Map<String, String>> edits,
            ToolContext toolContext) {
        // 先校验 path 和 edits，再校验 username（与原始代码保持一致）
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        if (edits == null || edits.isEmpty()) {
            return "错误：edits 不能为空";
        }
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.edit(basePath, path, edits);
    }

    // ==================== Directory operations ====================

    @Tool(description = "创建新目录或确保目录已存在，支持创建多级嵌套目录，如果目录已存在则静默成功。")
    @Override
    public String createDirectory(
            @ToolParam(description = "相对于用户文件目录的目录路径") String path,
            ToolContext toolContext) {
        // 先校验 path，再校验 username（与原始代码保持一致）
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.createDirectory(basePath, path);
    }

    // ==================== Move operation ====================

    @Tool(description = "移动或重命名本地文件。可以跨目录移动或仅重命名。")
    @Override
    public String moveFile(
            @ToolParam(description = "源文件路径（相对于用户文件目录）") String source,
            @ToolParam(description = "目标路径（相对于用户文件目录）") String destination,
            ToolContext toolContext) {
        // 先校验 source 和 destination，再校验 username（与原始代码保持一致）
        if (source == null || source.isBlank() || destination == null || destination.isBlank()) {
            return "错误：source 和 destination 不能为空";
        }
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.moveFile(basePath, source, destination);
    }

    // ==================== Search ====================

    @Tool(description = "在用户文件目录中递归搜索文件。pattern 为 glob 风格，如 '*.txt' 或 '**/*.java'。不传则返回所有文件（限制 maxSearchResults 条）。")
    @Override
    public String searchFiles(
            @ToolParam(description = "搜索模式（glob风格），如 '*.txt' 或 '**/*.java'。不传则返回所有文件", required = false) String pattern,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.searchFiles(basePath, pattern);
    }

    // ==================== List directories ====================

    @Tool(description = "列出当前用户的文件操作目录（返回的是**绝对路径**）。写入或创建文件时，path 参数均相对于此目录。")
    @Override
    public String listAllowedDirectories(ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path userDir = getUserFileDir(username).toAbsolutePath().normalize();
        try {
            // 确保用户目录存在（与 DefaultUpload.upload() 对齐）
            Files.createDirectories(userDir);
        } catch (IOException e) {
            return "错误：无法创建用户文件目录 " + userDir + "：" + e.getMessage();
        }
        Path example = userDir.resolve("notes/todo.txt");
        return "用户文件目录（绝对路径）：" + userDir + "\n\n" +
                "说明：所有文件操作的 path 参数均**相对于此目录**（不要拼绝对路径）。\n" +
                "例如：read_text_file 的 path 参数 'notes/todo.txt' 实际读取 '" + example + "'。\n" +
                "⚠️ 必须使用上面返回的精确绝对路径，不要用字符串拼接 / 路径替换重新构造。";
    }

    @Tool(description = "列出目录内容。区分文件 [FILE] 和目录 [DIR]。支持 depth 参数控制递归深度（受 maxWalkDepth 限制）。")
    @Override
    public String listDirectory(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串列出根目录") String path,
            @ToolParam(description = "递归深度（默认1，仅直接子项）", required = false) Integer depth,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.listDirectory(basePath, path, depth);
    }

    @Tool(description = "列出目录内容及每个项目的大小。区分文件 [FILE] 和目录 [DIR]。")
    @Override
    public String listDirectoryWithSizes(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串列出根目录") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.listDirectoryWithSizes(basePath, path);
    }

    @Tool(description = "获取目录的递归树视图，返回 JSON 格式结构。受 maxWalkDepth / maxWalkEntries / excludedDirs 限制。")
    @Override
    public String directoryTree(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串从根目录开始") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.directoryTree(basePath, path);
    }

    @Tool(description = "获取文件或目录的详细元数据，包括大小、创建时间、修改时间、权限等。")
    @Override
    public String getFileInfo(
            @ToolParam(description = "相对于用户文件目录的文件或目录路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);
        return fileOps.getFileInfo(basePath, path);
    }

    // ==================== Preview / Download (保留 fileId 模式) ====================

    @Tool(description = "根据文件路径生成原始文件下载链接。适用于需要获取文件原始二进制内容的场景。")
    @Override
    public String downloadFileUrl(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        Path filePath;
        try {
            filePath = resolvePathForRead(path, username);
        } catch (SecurityException | IOException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        if (!Files.isRegularFile(filePath)) {
            return "路径不是文件：" + path;
        }
        String fileId = getOrCreateFileId(filePath, username);
        if (fileId == null) {
            return "文件注册失败，无法生成下载链接";
        }
        String baseUrl = (String) toolContext.getContext().get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return "错误：上下文缺少 baseUrl，无法生成下载链接";
        }
        String url = baseUrl + "/spring/ai/loom/file/" + fileId + "/download";
        String fileName = filePath.getFileName().toString();
        return "文件名:" + fileName + "\n" +
                "下载链接:" + url + "\n" +
                "markdown格式:[下载:" + fileName + "](" + url + ")" + "\n";
    }

    @Tool(description = "根据文件路径生成文件在线预览链接。支持 PDF、Word、Excel、PPT、图片、Markdown 等格式。")
    @Override
    public String viewFileUrl(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            ToolContext toolContext) {
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        Path filePath;
        try {
            filePath = resolvePathForRead(path, username);
        } catch (SecurityException | IOException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        if (!Files.isRegularFile(filePath)) {
            return "路径不是文件：" + path;
        }
        String fileId = getOrCreateFileId(filePath, username);
        if (fileId == null) {
            return "文件注册失败，无法生成预览链接";
        }
        String baseUrl = (String) toolContext.getContext().get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            return "错误：上下文缺少 baseUrl，无法生成预览链接";
        }
        String url = baseUrl + "/file/view/" + fileId;
        String fileName = filePath.getFileName().toString();
        return "文件名:" + fileName + "\n" +
                "预览链接:" + url + "\n" +
                "markdown格式:[预览:" + fileName + "](" + url + ")" + "\n";
    }

    // ==================== Delete ====================

    @Tool(description = "删除本地文件或目录（递归删除目录与其内容）。必须显式确认（传入配置中的 deleteConfirmToken）才能执行。")
    @Override
    public String deleteFileOrDirectory(
            @ToolParam(description = "相对于用户文件目录的路径，可以是文件或或者目录") String path,
            @ToolParam(description = "确认字符串：必须与配置的 deleteConfirmToken 一致（默认 I_CONFIRM_DELETE）", required = false) String confirm,
            ToolContext toolContext) {
        // 校验顺序与原始代码保持一致：confirm -> path -> username
        String expected = cfg.getDeleteConfirmToken();
        if (confirm == null || !confirm.equals(expected)) {
            return "错误：需要确认。请传入 confirm=\"" + expected + "\" 才能执行删除。";
        }
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        String username = tryGetUsername(toolContext);
        if (username == null) return "错误：缺少 username 上下文";
        Path basePath = getUserFileDir(username);

        // 在删除前收集要删除的文件路径，用于后续清理元数据
        java.util.List<Path> toCleanup = new java.util.ArrayList<>();
        if (path != null && !path.isBlank()) {
            try {
                Path resolved = resolvePathForRead(path, username);
                if (Files.exists(resolved)) {
                    try (java.util.stream.Stream<Path> walk = Files.walk(resolved)) {
                        walk.filter(Files::isRegularFile).forEach(toCleanup::add);
                    }
                }
            } catch (Exception ignored) {
                // 收集失败不影响主流程
            }
        }

        // 执行删除（使用固定 token 通过 FileOperations 的检查）
        String result = fileOps.delete(basePath, path, expected);

        // 清理元数据
        if (result.startsWith("已删除")) {
            cleanupFileRecords(toCleanup, username);
        }

        return result;
    }

    /**
     * 删除已注册的临时文件元数据（usage='temp' 的记录）。
     */
    private void cleanupFileRecords(java.util.List<Path> paths, String username) {
        for (Path p : paths) {
            try {
                FileRecord rec = file.getByExactPath(p.toString(), username);
                if (rec != null && "temp".equals(rec.usage())) {
                    file.delete(rec.id(), username);
                }
            } catch (Exception ignored) {
                // 元数据清理失败不影响主流程
            }
        }
    }

    // ==================== Helpers ====================

    /**
     * 尝试从 ToolContext 获取 username，失败时返回 null。
     */
    private String tryGetUsername(ToolContext toolContext) {
        java.util.Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
        Object u = ctx == null ? null : ctx.get("username");
        if (u == null || u.toString().isBlank()) {
            return null;
        }
        return u.toString();
    }

    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private Path resolvePathForRead(String path, String username) throws IOException {
        Path baseDir = getUserFileDir(username);
        Path resolved;
        if (path == null || path.isEmpty() || ".".equals(path)) {
            resolved = baseDir.toAbsolutePath().normalize();
        } else {
            String normalized = path.replace('\\', java.io.File.separatorChar);
            resolved = baseDir.resolve(normalized).toAbsolutePath().normalize();
        }
        cn.wubo.loom.file.core.PathSecurityUtils.assertInsideBaseDir(resolved, baseDir, true);
        return resolved;
    }

    private String getOrCreateFileId(Path filePath, String username) {
        try {
            String pathStr = filePath.toString();
            FileRecord existing = file.getByExactPath(pathStr, username);
            if (existing != null) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                    if (attrs.size() != existing.size()) {
                        file.update(existing.id(), pathStr, filePath.getFileName().toString(), attrs.size(), username);
                    }
                } catch (Exception ignored) {
                    // 读取 / 更新失败时仍然返回已有 id
                }
                return existing.id();
            }

            String mimeType = TikaUtils.TIKA.detect(filePath.toFile());
            if (mimeType == null) mimeType = "application/octet-stream";
            String fileId = UUID.randomUUID().toString();
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            file.insert(new FileRecord(
                    fileId,
                    null,
                    filePath.getFileName().toString(),
                    attrs.size(),
                    LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault()),
                    pathStr,
                    "temp",
                    mimeType
            ), username);
            return fileId;
        } catch (Exception e) {
            return null;
        }
    }
}
