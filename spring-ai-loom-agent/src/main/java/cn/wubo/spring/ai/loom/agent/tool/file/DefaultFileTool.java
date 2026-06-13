package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.tool.common.PathSecurityUtils;
import org.apache.tika.Tika;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 文件工具默认实现。基于 {@link PathSecurityUtils} 做统一的 {@code ..} 越权 +
 * symlink 越界防御。
 * <p>
 * 关键约束（来自 {@link LoomAgentProperties.FileToolProperty}）：
 * <ul>
 *   <li>读 / 写 / 编辑受 {@code maxFileSize} 限制</li>
 *   <li>媒体文件（图片 / 音频）受 {@code maxMediaSize} 限制</li>
 *   <li>递归遍历受 {@code maxWalkDepth} / {@code maxWalkEntries} / {@code excludedDirs} 限制</li>
 *   <li>{@code searchFiles} 受 {@code maxSearchResults} 限制</li>
 *   <li>{@code deleteFileOrDirectory} 必须显式传入 {@code deleteConfirmToken}</li>
 *   <li>路径解析 + symlink 防御统一委托给 {@link PathSecurityUtils}</li>
 * </ul>
 */
public class DefaultFileTool implements IFileTool {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IFile file;
    private final String fileBasePath;
    private final LoomAgentProperties.FileToolProperty cfg;

    public DefaultFileTool(IFile file, String fileBasePath, LoomAgentProperties.FileToolProperty cfg) {
        this.file = file;
        this.fileBasePath = fileBasePath;
        this.cfg = cfg;
    }

    // ==================== Read operations ====================

    @Tool(description = "读取本地文件内容为文本。path 为相对于用户文件目录的路径。支持 head（仅前N行）或 tail（仅后N行）参数。")
    @Override
    public String readTextFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
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
        try {
            long size = Files.size(filePath);
            if (size > cfg.getMaxFileSize()) {
                return "错误：文件大小 " + formatSize(size) + " 超过限制 " + formatSize(cfg.getMaxFileSize())
                        + "，请使用 head/tail 局部读取";
            }
            List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            List<String> result;
            if (head != null && head > 0) {
                result = lines.stream().limit(head).toList();
            } else if (tail != null && tail > 0) {
                result = lines.stream().skip(Math.max(0, lines.size() - tail)).toList();
            } else {
                result = lines;
            }
            return String.join("\n", result);
        } catch (IOException e) {
            return "读取文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "读取本地图片或音频文件，返回 base64 编码数据和 MIME 类型。")
    @Override
    public String readMediaFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
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
        try {
            long size = Files.size(filePath);
            if (size > cfg.getMaxMediaSize()) {
                return "错误：媒体文件大小 " + formatSize(size) + " 超过限制 " + formatSize(cfg.getMaxMediaSize());
            }
            byte[] data = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(data);
            String mimeType = Files.probeContentType(filePath);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = "application/octet-stream";
            }
            return "MIME类型：" + mimeType + "\nBase64数据：\n" + base64;
        } catch (IOException e) {
            return "读取媒体文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "同时读取多个文件内容，比逐个读取更高效。单个文件读取失败不会影响其他文件。")
    @Override
    public String readMultipleFiles(
            @ToolParam(description = "要读取的文件路径列表（相对于用户文件目录）") List<String> paths,
            ToolContext toolContext) {
        if (paths == null || paths.isEmpty()) {
            return "错误：路径列表为空";
        }
        String username = requireUsername(toolContext);
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                sb.append("(空路径): 错误 - 路径不能为空\n---\n");
                continue;
            }
            Path filePath;
            try {
                filePath = resolvePathForRead(path, username);
            } catch (SecurityException | IOException e) {
                sb.append(path).append(": 错误 - ").append(e.getMessage()).append("\n---\n");
                continue;
            }
            if (!Files.exists(filePath)) {
                sb.append(path).append(": 错误 - 文件不存在\n---\n");
                continue;
            }
            if (!Files.isRegularFile(filePath)) {
                sb.append(path).append(": 错误 - 不是文件\n---\n");
                continue;
            }
            try {
                long size = Files.size(filePath);
                if (size > cfg.getMaxFileSize()) {
                    sb.append(path).append(": 错误 - 文件 ").append(formatSize(size))
                            .append(" 超过限制 ").append(formatSize(cfg.getMaxFileSize())).append("\n---\n");
                    continue;
                }
                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                sb.append(path).append(":\n").append(content).append("\n---\n");
            } catch (IOException e) {
                sb.append(path).append(": 错误 - ").append(e.getMessage()).append("\n---\n");
            }
        }
        return sb.toString();
    }

    // ==================== Write / Edit operations ====================

    @Tool(description = "创建新文件或完全覆盖已有文件内容。path 为相对于用户文件目录的路径。如果文件已存在则覆盖内容。")
    @Override
    public String writeFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "要写入的文本内容") String content,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            String username = requireUsername(toolContext);
            Path resolved = resolvePathForWrite(path, username);
            // 写入前先估算内容大小，避免超大字符串炸内存
            long contentSize = content == null ? 0L : content.getBytes(StandardCharsets.UTF_8).length;
            if (contentSize > cfg.getMaxFileSize()) {
                return "错误：写入内容 " + formatSize(contentSize) + " 超过限制 " + formatSize(cfg.getMaxFileSize());
            }
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            // 原子写：先写 .tmp，再 ATOMIC_MOVE 替换
            Path tmp = resolved.resolveSibling(resolved.getFileName().toString() + ".tmp." + UUID.randomUUID());
            try {
                if (content == null) {
                    Files.writeString(tmp, "", StandardCharsets.UTF_8);
                } else {
                    Files.writeString(tmp, content, StandardCharsets.UTF_8);
                }
                try {
                    Files.move(tmp, resolved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException amns) {
                    // 跨卷 / 文件系统不支持原子移动时退化到非原子替换
                    Files.move(tmp, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return "文件已写入：" + path;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "写入文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "对本地文件进行基于行的编辑，每次编辑用新内容替换精确匹配的文本序列，返回 git 风格的 diff。")
    @Override
    public String editFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "编辑列表，每个编辑包含 oldText（要替换的文本）和 newText（替换后的文本）") List<Map<String, String>> edits,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        if (edits == null || edits.isEmpty()) {
            return "错误：edits 不能为空";
        }
        String username = requireUsername(toolContext);
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
        String fileName = filePath.getFileName().toString();
        try {
            long size = Files.size(filePath);
            if (size > cfg.getMaxFileSize()) {
                return "错误：文件 " + formatSize(size) + " 超过限制 " + formatSize(cfg.getMaxFileSize());
            }
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            StringBuilder diff = new StringBuilder();
            diff.append("--- ").append(fileName).append("\n");
            diff.append("+++ ").append(fileName).append("\n");

            String newContent = content;
            for (int idx = 0; idx < edits.size(); idx++) {
                Map<String, String> edit = edits.get(idx);
                String oldText = edit.get("oldText");
                String newText = edit.get("newText");
                if (oldText == null || newText == null) {
                    return "错误：第 " + (idx + 1) + " 个编辑必须包含 oldText 和 newText 字段";
                }
                if (oldText.isEmpty()) {
                    return "错误：第 " + (idx + 1) + " 个编辑的 oldText 不能为空";
                }
                // 唯一性校验：避免 LLM 误传 oldText 导致多次误替换
                int first = newContent.indexOf(oldText);
                int last = newContent.lastIndexOf(oldText);
                if (first < 0) {
                    return "错误：在文件中未找到文本 '" + truncate(oldText, 80) + "'";
                }
                if (first != last) {
                    return "错误：文本 '" + truncate(oldText, 80) + "' 在文件中出现 "
                            + countOccurrences(newContent, oldText) + " 次，请提供更精确的上下文使其唯一";
                }
                newContent = newContent.substring(0, first) + newText + newContent.substring(first + oldText.length());

                diff.append("@@ 编辑 ").append(idx + 1).append(" @@\n");
                diff.append("-").append(oldText.replace("\n", "\\n")).append("\n");
                diff.append("+").append(newText.replace("\n", "\\n")).append("\n");
            }

            long newSize = newContent.getBytes(StandardCharsets.UTF_8).length;
            if (newSize > cfg.getMaxFileSize()) {
                return "错误：编辑后文件 " + formatSize(newSize) + " 超过限制 " + formatSize(cfg.getMaxFileSize());
            }
            // 原子写
            Path tmp = filePath.resolveSibling(filePath.getFileName().toString() + ".tmp." + UUID.randomUUID());
            try {
                Files.writeString(tmp, newContent, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException amns) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }

            diff.insert(0, "已应用 " + edits.size() + " 处编辑到 " + fileName + "\n\n");
            return diff.toString();
        } catch (IOException e) {
            return "编辑文件失败：" + e.getMessage();
        }
    }

    // ==================== Directory operations ====================

    @Tool(description = "创建新目录或确保目录已存在，支持创建多级嵌套目录，如果目录已存在则静默成功。")
    @Override
    public String createDirectory(
            @ToolParam(description = "相对于用户文件目录的目录路径") String path,
            ToolContext toolContext) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            String username = requireUsername(toolContext);
            Path resolved = resolvePathForWrite(path, username);
            if (Files.exists(resolved)) {
                if (Files.isDirectory(resolved)) {
                    return "目录已存在：" + path;
                }
                return "错误：路径已存在且不是目录 - " + path;
            }
            Files.createDirectories(resolved);
            return "目录已创建：" + path;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "创建目录失败：" + e.getMessage();
        }
    }

    // ==================== Move operation ====================

    @Tool(description = "移动或重命名本地文件。可以跨目录移动或仅重命名。")
    @Override
    public String moveFile(
            @ToolParam(description = "源文件路径（相对于用户文件目录）") String source,
            @ToolParam(description = "目标路径（相对于用户文件目录）") String destination,
            ToolContext toolContext) {
        if (source == null || source.isBlank() || destination == null || destination.isBlank()) {
            return "错误：source 和 destination 不能为空";
        }
        String username = requireUsername(toolContext);
        try {
            Path resolvedSource = resolvePathForRead(source, username);
            Path resolvedDest = resolvePathForWrite(destination, username);
            if (!Files.exists(resolvedSource)) {
                return "源文件不存在：" + source;
            }
            if (Files.exists(resolvedDest)) {
                return "错误：目标路径已存在 - " + destination + "（如需覆盖请先删除目标）";
            }
            Path parent = resolvedDest.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try {
                Files.move(resolvedSource, resolvedDest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException amns) {
                // 跨卷 / 同盘内不同文件系统不支持 ATOMIC_MOVE 时退化
                Files.move(resolvedSource, resolvedDest);
            }
            return "已移动：" + source + " -> " + destination;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "移动文件失败：" + e.getMessage();
        }
    }

    // ==================== Search ====================

    @Tool(description = "在用户文件目录中递归搜索文件。pattern 为 glob 风格，如 '*.txt' 或 '**/*.java'。不传则返回所有文件（限制 maxSearchResults 条）。")
    @Override
    public String searchFiles(
            @ToolParam(description = "搜索模式（glob风格），如 '*.txt' 或 '**/*.java'。不传则返回所有文件", required = false) String pattern,
            ToolContext toolContext) {
        try {
            String username = requireUsername(toolContext);
            Path baseDir = getUserFileDir(username);
            if (!Files.exists(baseDir)) {
                return "用户文件目录不存在";
            }

            // 防止 LLM 漏写 "glob:" 前缀
            String effectivePattern = pattern;
            if (effectivePattern != null && !effectivePattern.isBlank()
                    && !effectivePattern.startsWith("glob:") && !effectivePattern.startsWith("regex:")) {
                effectivePattern = "glob:" + effectivePattern;
            }
            PathMatcher matcher = (effectivePattern != null && !effectivePattern.isBlank())
                    ? baseDir.getFileSystem().getPathMatcher(effectivePattern)
                    : null;
            int maxResults = cfg.getMaxSearchResults();
            int maxDepth = cfg.getMaxWalkDepth();
            Set<String> excluded = new HashSet<>(cfg.getExcludedDirs());

            StringBuilder sb = new StringBuilder();
            int matched = 0;
            int truncated = 0;
            int totalScanned = 0;
            int entryCap = cfg.getMaxWalkEntries();
            try (Stream<Path> walk = Files.walk(baseDir, maxDepth)) {
                var filtered = walk
                        .filter(p -> !excluded.contains(p.getFileName() == null ? "" : p.getFileName().toString()))
                        .filter(Files::isRegularFile);
                if (matcher != null) {
                    filtered = filtered.filter(p -> matcher.matches(baseDir.relativize(p)));
                }
                Iterator<Path> it = filtered.iterator();
                while (it.hasNext()) {
                    totalScanned++;
                    if (totalScanned > entryCap) {
                        break; // 超过遍历上限直接停
                    }
                    Path f = it.next();
                    if (matched >= maxResults) {
                        truncated++;
                        continue;
                    }
                    String relPath = baseDir.relativize(f).toString().replace(File.separatorChar, '/');
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
                        sb.append("路径: ").append(relPath)
                                .append(" | 大小: ").append(formatSize(attrs.size()))
                                .append(" | 修改: ").append(formatInstant(attrs.lastModifiedTime().toInstant()))
                                .append("\n");
                    } catch (IOException e) {
                        sb.append("路径: ").append(relPath).append("\n");
                    }
                    matched++;
                }
            }
            if (matched == 0 && truncated == 0) {
                return "未找到匹配的文件";
            }
            sb.insert(0, String.format("匹配 %d 个文件（最多 %d 条；扫描上限 %d）%n%n", matched, maxResults, entryCap));
            if (truncated > 0) {
                sb.append("… 还有 ").append(truncated).append(" 条匹配未列出（已达 maxSearchResults=").append(maxResults).append("）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索文件失败：" + e.getMessage();
        }
    }

    // ==================== List directories ====================

    @Tool(description = "列出当前用户的文件操作目录（返回的是**绝对路径**）。写入或创建文件时，path 参数均相对于此目录。")
    @Override
    public String listAllowedDirectories(ToolContext toolContext) {
        String username = requireUsername(toolContext);
        Path userDir = getUserFileDir(username).toAbsolutePath().normalize();
        try {
            // 确保用户目录存在（与 DefaultUpload.upload() 对齐）—— 否则首次写文件时
            // PathSecurityUtils 会因 userDir 不存在、且其最近祖先在 userDir 之上而误判
            // "路径通过 symlink 越界"。listAllowedDirectories 是绝大多数 skill（特别是
            // news-watch.st）的前置调用，在这里建一次目录最干净。
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
        String username = requireUsername(toolContext);
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(effectivePath, username);
        } catch (SecurityException | IOException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            int d = (depth != null && depth > 0) ? Math.min(depth, cfg.getMaxWalkDepth()) : 1;
            StringBuilder sb = new StringBuilder();
            int[] counters = new int[]{0}; // 包装为数组便于在 lambda 中修改
            int truncated = listDirRecursive(dirPath, "", d, sb, counters, cfg);
            if (truncated > 0) {
                sb.append("… 还有 ").append(truncated).append(" 个条目未列出（已达 maxWalkEntries=")
                        .append(cfg.getMaxWalkEntries()).append("）\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "列出目录失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出目录内容及每个项目的大小。区分文件 [FILE] 和目录 [DIR]。")
    @Override
    public String listDirectoryWithSizes(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串列出根目录") String path,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(effectivePath, username);
        } catch (SecurityException | IOException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> stream = Files.list(dirPath)) {
                // 字典序排序，目录和文件混排（不再"目录优先"），更符合 ls 的视觉
                var items = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (Path item : items) {
                    String name = item.getFileName().toString();
                    if (Files.isDirectory(item)) {
                        sb.append("[DIR]  ").append(name).append("\n");
                    } else {
                        long size = Files.size(item);
                        sb.append("[FILE] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                    }
                }
            }
            return sb.length() == 0 ? "目录为空" : sb.toString();
        } catch (IOException e) {
            return "列出目录失败：" + e.getMessage();
        }
    }

    @Tool(description = "获取目录的递归树视图，返回 JSON 格式结构。受 maxWalkDepth / maxWalkEntries / excludedDirs 限制。")
    @Override
    public String directoryTree(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串从根目录开始") String path,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(effectivePath, username);
        } catch (SecurityException | IOException e) {
            return "错误：" + e.getMessage();
        }
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            int[] counters = new int[]{0};
            Map<String, Object> tree = buildDirectoryTree(dirPath, 0, cfg, counters);
            if (counters[0] >= cfg.getMaxWalkEntries()) {
                tree.put("_truncated", true);
                tree.put("_truncatedMsg", "已达 maxWalkEntries=" + cfg.getMaxWalkEntries() + "，部分子树未展开");
            }
            return toJson(tree, 0);
        } catch (IOException e) {
            return "生成目录树失败：" + e.getMessage();
        }
    }

    @Tool(description = "获取文件或目录的详细元数据，包括大小、创建时间、修改时间、权限等。")
    @Override
    public String getFileInfo(
            @ToolParam(description = "相对于用户文件目录的文件或目录路径") String path,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
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
            return "路径不存在：" + path;
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
            StringBuilder sb = new StringBuilder();
            sb.append("名称: ").append(filePath.getFileName()).append("\n");
            sb.append("类型: ").append(attrs.isDirectory() ? "目录" : "文件").append("\n");
            sb.append("大小: ").append(attrs.isDirectory() ? "—" : formatSize(attrs.size())).append("\n");
            sb.append("创建时间: ").append(formatInstant(attrs.creationTime().toInstant())).append("\n");
            sb.append("最后修改: ").append(formatInstant(attrs.lastModifiedTime().toInstant())).append("\n");
            sb.append("最后访问: ").append(formatInstant(attrs.lastAccessTime().toInstant())).append("\n");
            if (attrs.isRegularFile()) {
                long size = attrs.size();
                if (size > cfg.getMaxFileSize()) {
                    sb.append("行数: 跳过（文件 ").append(formatSize(size))
                            .append(" 超过 maxFileSize=").append(formatSize(cfg.getMaxFileSize())).append("）\n");
                } else {
                    try (var lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                        long lineCount = lines.count();
                        sb.append("行数: ").append(lineCount).append("\n");
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
            return sb.toString();
        } catch (IOException e) {
            return "获取文件信息失败：" + e.getMessage();
        }
    }

    // ==================== Preview / Download (保留 fileId 模式) ====================

    @Tool(description = "根据文件路径生成原始文件下载链接。适用于需要获取文件原始二进制内容的场景。")
    @Override
    public String downloadFileUrl(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            ToolContext toolContext) {
        String username = requireUsername(toolContext);
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
        String url = baseUrl + "/wopi/files/" + fileId + "/contents";
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
        String username = requireUsername(toolContext);
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

    @Tool(description = "删除本地文件或目录（递归删除目录及其内容）。必须显式确认（传入配置中的 deleteConfirmToken）才能执行。")
    @Override
    public String deleteFileOrDirectory(
            @ToolParam(description = "相对于用户文件目录的路径，可以是文件或目录") String path,
            @ToolParam(description = "确认字符串：必须与配置的 deleteConfirmToken 一致（默认 I_CONFIRM_DELETE）", required = false) String confirm,
            ToolContext toolContext) {
        String expected = cfg.getDeleteConfirmToken();
        if (confirm == null || !confirm.equals(expected)) {
            return "错误：需要确认。请传入 confirm=\"" + expected + "\" 才能执行删除。";
        }
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            String username = requireUsername(toolContext);
            Path resolved = resolvePathForRead(path, username);
            if (!Files.exists(resolved)) {
                return "路径不存在：" + path;
            }
            boolean wasDirectory = Files.isDirectory(resolved);

            // 一次 walk 拿到 (待清理元数据列表, 待删除文件列表)，避免对大目录遍历两次
            List<Path> toCleanup = new ArrayList<>();
            List<Path> toDelete = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(resolved)) {
                Iterator<Path> it = walk.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (Files.isRegularFile(p)) {
                        toCleanup.add(p);
                        toDelete.add(p);
                    } else if (Files.isDirectory(p)) {
                        // 目录不清理元数据（只清理文件记录）
                        // 目录本身要删，加入到 toDelete
                        toDelete.add(p);
                    }
                }
            }
            // 排序：深 → 浅
            toDelete.sort(Comparator.comparingInt(Path::getNameCount).reversed());

            int deleted = 0;
            for (Path p : toDelete) {
                Files.delete(p);
                if (Files.isRegularFile(p)) {
                    deleted++;
                }
            }
            cleanupFileRecords(toCleanup, username);
            return wasDirectory ? "已删除目录：" + path + "（" + deleted + " 个文件）"
                    : "已删除文件：" + path;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "删除失败：" + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 删除已注册的临时文件元数据（usage='temp' 的记录）。
     * 删除失败不影响主流程（FS 已删，元数据残留由后续 GC 处理）。
     */
    private void cleanupFileRecords(List<Path> paths, String username) {
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

    private String requireUsername(ToolContext toolContext) {
        Map<String, Object> ctx = toolContext == null ? null : toolContext.getContext();
        Object u = ctx == null ? null : ctx.get("username");
        if (u == null || u.toString().isBlank()) {
            throw new SecurityException("缺少 username 上下文");
        }
        return u.toString();
    }

    /** 用于读 / 删 / 查：路径必须存在，调用 PathSecurityUtils 做 symlink 跟链 */
    private Path resolvePathForRead(String path, String username) throws IOException {
        Path resolved = resolveRawPath(path, username);
        PathSecurityUtils.assertInsideUserDir(resolved, getUserFileDir(username), true);
        return resolved;
    }

    /** 用于写 / 创建：路径可能还不存在，沿祖先链做 symlink 防御 */
    private Path resolvePathForWrite(String path, String username) throws IOException {
        Path resolved = resolveRawPath(path, username);
        PathSecurityUtils.assertInsideUserDir(resolved, getUserFileDir(username), false);
        return resolved;
    }

    /**
     * 把 LLM 给的相对路径解析为绝对路径，规范化、替换分隔符。
     * 安全检查委托给 {@link PathSecurityUtils}。
     */
    private Path resolveRawPath(String path, String username) {
        if (path == null) {
            // null 当作根目录，便于 listAllowedDirectories 等场景
            return getUserFileDir(username).toAbsolutePath().normalize();
        }
        Path base = getUserFileDir(username);
        if (path.isEmpty() || ".".equals(path)) {
            return base.toAbsolutePath().normalize();
        }
        // 把 LLM 可能传的 \ 统一成系统分隔符（Windows 上"abc/def" 也能解析）
        String normalized = path.replace('\\', File.separatorChar);
        return base.resolve(normalized).toAbsolutePath().normalize();
    }

    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private String getOrCreateFileId(Path filePath, String username) {
        try {
            String pathStr = filePath.toString();
            // 已有记录则更新 size/modifiedTime 后直接返回 id（upsert）
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

            Tika tika = new Tika();
            String mimeType = tika.detect(filePath.toFile());
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

    /**
     * 递归列目录。返回被截断（未列出）的条目数。
     */
    private int listDirRecursive(Path dir, String indent, int depth, StringBuilder sb,
                                 int[] counters, LoomAgentProperties.FileToolProperty cfg) throws IOException {
        Set<String> excluded = new HashSet<>(cfg.getExcludedDirs());
        int entryCap = cfg.getMaxWalkEntries();
        int truncated = 0;
        try (Stream<Path> stream = Files.list(dir)) {
            var items = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path item : items) {
                String name = item.getFileName().toString();
                if (excluded.contains(name)) {
                    sb.append(indent).append("[SKIP] ").append(name).append("（在 excludedDirs 中）\n");
                    continue;
                }
                if (counters[0] >= entryCap) {
                    truncated++;
                    continue;
                }
                if (Files.isDirectory(item)) {
                    sb.append(indent).append("[DIR]  ").append(name).append("\n");
                    counters[0]++;
                    if (depth > 1) {
                        truncated += listDirRecursive(item, indent + "  ", depth - 1, sb, counters, cfg);
                    }
                } else {
                    long size = Files.size(item);
                    sb.append(indent).append("[FILE] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                    counters[0]++;
                }
            }
        }
        return truncated;
    }

    private Map<String, Object> buildDirectoryTree(Path dir, int depth,
                                                   LoomAgentProperties.FileToolProperty cfg,
                                                   int[] counters) throws IOException {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", dir.getFileName() != null ? dir.getFileName().toString() : ".");
        node.put("type", "directory");

        if (depth >= cfg.getMaxWalkDepth()) {
            node.put("children", List.of());
            node.put("_note", "已达 maxWalkDepth=" + cfg.getMaxWalkDepth());
            return node;
        }
        if (counters[0] >= cfg.getMaxWalkEntries()) {
            node.put("children", List.of());
            return node;
        }
        Set<String> excluded = new HashSet<>(cfg.getExcludedDirs());
        List<Map<String, Object>> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            var sorted = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path item : sorted) {
                String name = item.getFileName().toString();
                if (excluded.contains(name)) continue;
                if (counters[0] >= cfg.getMaxWalkEntries()) {
                    node.put("_truncated", true);
                    break;
                }
                counters[0]++;
                if (Files.isDirectory(item)) {
                    children.add(buildDirectoryTree(item, depth + 1, cfg, counters));
                } else {
                    Map<String, Object> fileNode = new LinkedHashMap<>();
                    fileNode.put("name", name);
                    fileNode.put("type", "file");
                    try {
                        fileNode.put("size", Files.size(item));
                    } catch (IOException ignored) {
                        fileNode.put("size", 0);
                    }
                    children.add(fileNode);
                }
            }
        }
        node.put("children", children);
        return node;
    }

    private String toJson(Map<String, Object> map, int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = "  ".repeat(indent);
        String childPad = "  ".repeat(indent + 1);
        sb.append("{\n");
        int i = 0;
        for (var entry : map.entrySet()) {
            sb.append(childPad).append("\"").append(escapeJson(entry.getKey())).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (value instanceof Number n) {
                sb.append(n);
            } else if (value instanceof Boolean b) {
                sb.append(b);
            } else if (value instanceof List<?> list) {
                sb.append(toJsonList(list, indent + 1));
            } else if (value instanceof Map m) {
                sb.append(toJson((Map<String, Object>) m, indent + 1));
            } else {
                sb.append("null");
            }
            if (i < map.size() - 1) sb.append(",");
            sb.append("\n");
            i++;
        }
        sb.append(pad).append("}");
        return sb.toString();
    }

    private String toJsonList(List<?> list, int indent) {
        StringBuilder sb = new StringBuilder();
        String pad = "  ".repeat(indent);
        String childPad = "  ".repeat(indent + 1);
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append(childPad);
            Object item = list.get(i);
            if (item instanceof Map m) {
                sb.append(toJson((Map<String, Object>) m, indent + 1));
            } else if (item instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (item instanceof Number n) {
                sb.append(n);
            } else if (item instanceof Boolean b) {
                sb.append(b);
            } else {
                sb.append(item);
            }
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(pad).append("]");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String formatInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(DTF);
    }
}
