package cn.wubo.loom.file.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 核心文件操作类。提供基于路径的文件读写、编辑、目录操作等功能。
 * <p>
 * 所有方法都接受 {@code basePath} 参数，调用方负责确定基础路径。
 * 本类不包含任何用户隔离逻辑，纯文件操作。
 * </p>
 * <p>
 * 关键约束（通过 {@link Config} 配置）：
 * <ul>
 * <li>读 / 写 / 编辑受 {@code maxFileSize} 限制</li>
 * <li>媒体文件受 {@code maxMediaSize} 限制</li>
 * <li>递归遍历受 {@code maxWalkDepth} / {@code maxWalkEntries} / {@code excludedDirs} 限制</li>
 * <li>{@code searchFiles} 受 {@code maxSearchResults} 限制</li>
 * <li>{@code delete} 需要显式传入 {@code deleteConfirmToken}</li>
 * <li>路径解析 + symlink 防御统一委托给 {@link PathSecurityUtils}</li>
 * </ul>
 */
public class FileOperations {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Config cfg;

    public FileOperations(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg");
    }

    public FileOperations() {
        this(Config.defaults());
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
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

    // ==================== Read operations ====================

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private static String formatInstant(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).format(DTF);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ==================== Write / Edit operations ====================

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * 读取文本文件。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @param head     仅返回前 N 行（可选）
     * @param tail     仅返回后 N 行（可选）
     * @return 文件内容或错误信息
     */
    public String readText(Path basePath, String path, Integer head, Integer tail) {
        Path filePath;
        try {
            filePath = resolvePathForRead(basePath, path);
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
            if (size > cfg.maxFileSize()) {
                return "错误：文件大小 " + formatSize(size) + " 超过限制 " + formatSize(cfg.maxFileSize())
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

    // ==================== Directory operations ====================

    /**
     * 读取媒体文件（图片/音频），返回 base64 编码。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @return MIME 类型和 base64 数据，或错误信息
     */
    public String readMedia(Path basePath, String path) {
        Path filePath;
        try {
            filePath = resolvePathForRead(basePath, path);
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
            if (size > cfg.maxMediaSize()) {
                return "错误：媒体文件大小 " + formatSize(size) + " 超过限制 " + formatSize(cfg.maxMediaSize());
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

    // ==================== Move operation ====================

    /**
     * 批量读取多个文件。
     *
     * @param basePath 基础目录
     * @param paths    相对路径列表
     * @return 所有文件内容，用分隔符分隔
     */
    public String readMultiple(Path basePath, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return "错误：路径列表为空";
        }
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                sb.append("(空路径): 错误 - 路径不能为空\n---\n");
                continue;
            }
            Path filePath;
            try {
                filePath = resolvePathForRead(basePath, path);
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
                if (size > cfg.maxFileSize()) {
                    sb.append(path).append(": 错误 - 文件 ").append(formatSize(size))
                            .append(" 超过限制 ").append(formatSize(cfg.maxFileSize())).append("\n---\n");
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

    // ==================== Search ====================

    /**
     * 写入文件（覆盖或新建）。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @param content  文件内容
     * @return 操作结果
     */
    public String write(Path basePath, String path, String content) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            Path resolved = resolvePathForWrite(basePath, path);
            long contentSize = content == null ? 0L : content.getBytes(StandardCharsets.UTF_8).length;
            if (contentSize > cfg.maxFileSize()) {
                return "错误：写入内容 " + formatSize(contentSize) + " 超过限制 " + formatSize(cfg.maxFileSize());
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

    // ==================== List directories ====================

    /**
     * 编辑文件（基于文本替换）。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @param edits    编辑列表，每个编辑包含 oldText 和 newText
     * @return 编辑结果（diff 格式）
     */
    public String edit(Path basePath, String path, List<Map<String, String>> edits) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        if (edits == null || edits.isEmpty()) {
            return "错误：edits 不能为空";
        }
        Path filePath;
        try {
            filePath = resolvePathForRead(basePath, path);
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
            if (size > cfg.maxFileSize()) {
                return "错误：文件 " + formatSize(size) + " 超过限制 " + formatSize(cfg.maxFileSize());
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
            if (newSize > cfg.maxFileSize()) {
                return "错误：编辑后文件 " + formatSize(newSize) + " 超过限制 " + formatSize(cfg.maxFileSize());
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

    /**
     * 创建目录。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @return 操作结果
     */
    public String createDirectory(Path basePath, String path) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            Path resolved = resolvePathForWrite(basePath, path);
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

    /**
     * 移动或重命名文件。
     *
     * @param basePath    基础目录
     * @param source      源路径
     * @param destination 目标路径
     * @return 操作结果
     */
    public String moveFile(Path basePath, String source, String destination) {
        if (source == null || source.isBlank() || destination == null || destination.isBlank()) {
            return "错误：source 和 destination 不能为空";
        }
        try {
            Path resolvedSource = resolvePathForRead(basePath, source);
            Path resolvedDest = resolvePathForWrite(basePath, destination);
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
                Files.move(resolvedSource, resolvedDest);
            }
            return "已移动：" + source + " -> " + destination;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "移动文件失败：" + e.getMessage();
        }
    }

    /**
     * 搜索文件。
     *
     * @param basePath 基础目录
     * @param pattern  glob 风格匹配模式（可选）
     * @return 匹配的文件列表
     */
    public String searchFiles(Path basePath, String pattern) {
        try {
            if (!Files.exists(basePath)) {
                return "基础目录不存在";
            }

            String effectivePattern = pattern;
            if (effectivePattern != null && !effectivePattern.isBlank()
                    && !effectivePattern.startsWith("glob:") && !effectivePattern.startsWith("regex:")) {
                effectivePattern = "glob:" + effectivePattern;
            }
            PathMatcher matcher = (effectivePattern != null && !effectivePattern.isBlank())
                    ? basePath.getFileSystem().getPathMatcher(effectivePattern)
                    : null;
            int maxResults = cfg.maxSearchResults();
            int maxDepth = cfg.maxWalkDepth();
            Set<String> excluded = new HashSet<>(cfg.excludedDirs());

            StringBuilder sb = new StringBuilder();
            int matched = 0;
            int truncated = 0;
            int totalScanned = 0;
            int entryCap = cfg.maxWalkEntries();
            try (Stream<Path> walk = Files.walk(basePath, maxDepth)) {
                var filtered = walk
                        .filter(p -> !excluded.contains(p.getFileName() == null ? "" : p.getFileName().toString()))
                        .filter(Files::isRegularFile);
                if (matcher != null) {
                    filtered = filtered.filter(p -> matcher.matches(basePath.relativize(p)));
                }
                Iterator<Path> it = filtered.iterator();
                while (it.hasNext()) {
                    totalScanned++;
                    if (totalScanned > entryCap) {
                        break;
                    }
                    Path f = it.next();
                    if (matched >= maxResults) {
                        truncated++;
                        continue;
                    }
                    String relPath = basePath.relativize(f).toString().replace(File.separatorChar, '/');
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

    /**
     * 获取基础目录的绝对路径。
     *
     * @param basePath 基础目录
     * @return 目录信息
     */
    public String listBaseDirectory(Path basePath) {
        Path baseDir = basePath.toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            return "错误：无法创建基础目录 " + baseDir + "：" + e.getMessage();
        }
        Path example = baseDir.resolve("notes/todo.txt");
        return "基础目录（绝对路径）：" + baseDir + "\n\n" +
                "说明：所有文件操作的 path 参数均**相对于此目录**（不要拼绝对路径）。\n" +
                "例如：readText 的 path 参数 'notes/todo.txt' 实际读取 '" + example + "'。\n" +
                "⚠️ 必须使用上面返回的精确绝对路径，不要用字符串拼接 / 路径替换重新构造。";
    }

    // ==================== Delete ====================

    /**
     * 列出目录内容。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @param depth    递归深度
     * @return 目录内容
     */
    public String listDirectory(Path basePath, String path, Integer depth) {
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(basePath, effectivePath);
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
            int d = (depth != null && depth > 0) ? Math.min(depth, cfg.maxWalkDepth()) : 1;
            StringBuilder sb = new StringBuilder();
            int[] counters = new int[]{0};
            int truncated = listDirRecursive(dirPath, "", d, sb, counters);
            if (truncated > 0) {
                sb.append("… 还有 ").append(truncated).append(" 个条目未列出（已达 maxWalkEntries=")
                        .append(cfg.maxWalkEntries()).append("）\n");
            }
            return sb.toString();
        } catch (IOException e) {
            return "列出目录失败：" + e.getMessage();
        }
    }

    // ==================== Path resolution helpers ====================

    /**
     * 列出目录内容及大小。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @return 目录内容及大小
     */
    public String listDirectoryWithSizes(Path basePath, String path) {
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(basePath, effectivePath);
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
                var items = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                for (Path item : items) {
                    String name = item.getFileName().toString();
                    if (Files.isDirectory(item)) {
                        sb.append("[DIR] ").append(name).append("\n");
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

    /**
     * 获取目录树（JSON 格式）。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @return JSON 格式的目录树
     */
    public String directoryTree(Path basePath, String path) {
        String effectivePath = (path == null || path.isEmpty()) ? "." : path;
        Path dirPath;
        try {
            dirPath = resolvePathForRead(basePath, effectivePath);
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
            Map<String, Object> tree = buildDirectoryTree(dirPath, 0, counters);
            if (counters[0] >= cfg.maxWalkEntries()) {
                tree.put("_truncated", true);
                tree.put("_truncatedMsg", "已达 maxWalkEntries=" + cfg.maxWalkEntries() + "，部分子树未展开");
            }
            return toJson(tree, 0);
        } catch (IOException e) {
            return "生成目录树失败：" + e.getMessage();
        }
    }

    /**
     * 获取文件或目录的详细信息。
     *
     * @param basePath 基础目录
     * @param path     相对路径
     * @return 文件/目录详情
     */
    public String getFileInfo(Path basePath, String path) {
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        Path filePath;
        try {
            filePath = resolvePathForRead(basePath, path);
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
                if (size > cfg.maxFileSize()) {
                    sb.append("行数: 跳过（文件 ").append(formatSize(size))
                            .append(" 超过 maxFileSize=").append(formatSize(cfg.maxFileSize())).append("）\n");
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

    // ==================== Display helpers ====================

    /**
     * 删除文件或目录（递归）。
     *
     * @param basePath     基础目录
     * @param path         相对路径
     * @param confirmToken 确认令牌
     * @return 操作结果
     */
    public String delete(Path basePath, String path, String confirmToken) {
        if (confirmToken == null || !confirmToken.equals(cfg.deleteConfirmToken())) {
            return "错误：需要确认。请传入 confirm=\"" + cfg.deleteConfirmToken() + "\" 才能执行删除。";
        }
        if (path == null || path.isBlank()) {
            return "错误：path 不能为空";
        }
        try {
            Path resolved = resolvePathForRead(basePath, path);
            if (!Files.exists(resolved)) {
                return "路径不存在：" + path;
            }
            boolean wasDirectory = Files.isDirectory(resolved);

            List<Path> toDelete = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(resolved)) {
                Iterator<Path> it = walk.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (Files.isRegularFile(p) || Files.isDirectory(p)) {
                        toDelete.add(p);
                    }
                }
            }
            toDelete.sort(Comparator.comparingInt(Path::getNameCount).reversed());

            int deleted = 0;
            for (Path p : toDelete) {
                Files.delete(p);
                if (Files.isRegularFile(p)) {
                    deleted++;
                }
            }
            return wasDirectory ? "已删除目录：" + path + "（" + deleted + " 个文件）"
                    : "已删除文件：" + path;
        } catch (SecurityException e) {
            return "错误：" + e.getMessage();
        } catch (IOException e) {
            return "删除失败：" + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /**
     * 用于读 / 删 / 查：路径必须存在，调用 PathSecurityUtils 做 symlink 跟链。
     */
    private Path resolvePathForRead(Path basePath, String path) throws IOException {
        Path resolved = resolveRawPath(basePath, path);
        PathSecurityUtils.assertInsideBaseDir(resolved, basePath, true);
        return resolved;
    }

    /**
     * 用于写 / 创建：路径可能还不存在，沿祖先链做 symlink 防御。
     */
    private Path resolvePathForWrite(Path basePath, String path) throws IOException {
        Path resolved = resolveRawPath(basePath, path);
        PathSecurityUtils.assertInsideBaseDir(resolved, basePath, false);
        return resolved;
    }

    /**
     * 把相对路径解析为绝对路径，规范化、替换分隔符。
     */
    private Path resolveRawPath(Path basePath, String path) {
        if (path == null) {
            return basePath.toAbsolutePath().normalize();
        }
        if (path.isEmpty() || ".".equals(path)) {
            return basePath.toAbsolutePath().normalize();
        }
        String normalized = path.replace('\\', File.separatorChar);
        return basePath.resolve(normalized).toAbsolutePath().normalize();
    }

    /**
     * 递归列目录。返回被截断的条目数。
     */
    private int listDirRecursive(Path dir, String indent, int depth, StringBuilder sb,
                                 int[] counters) throws IOException {
        Set<String> excluded = new HashSet<>(cfg.excludedDirs());
        int entryCap = cfg.maxWalkEntries();
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
                    sb.append(indent).append("[DIR] ").append(name).append("\n");
                    counters[0]++;
                    if (depth > 1) {
                        truncated += listDirRecursive(item, indent + " ", depth - 1, sb, counters);
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

    private Map<String, Object> buildDirectoryTree(Path dir, int depth, int[] counters) throws IOException {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", dir.getFileName() != null ? dir.getFileName().toString() : ".");
        node.put("type", "directory");

        if (depth >= cfg.maxWalkDepth()) {
            node.put("children", List.of());
            node.put("_note", "已达 maxWalkDepth=" + cfg.maxWalkDepth());
            return node;
        }
        if (counters[0] >= cfg.maxWalkEntries()) {
            node.put("children", List.of());
            return node;
        }
        Set<String> excluded = new HashSet<>(cfg.excludedDirs());
        List<Map<String, Object>> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            var sorted = stream.sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path item : sorted) {
                String name = item.getFileName().toString();
                if (excluded.contains(name)) continue;
                if (counters[0] >= cfg.maxWalkEntries()) {
                    node.put("_truncated", true);
                    break;
                }
                counters[0]++;
                if (Files.isDirectory(item)) {
                    children.add(buildDirectoryTree(item, depth + 1, counters));
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
        String pad = " ".repeat(indent);
        String childPad = " ".repeat(indent + 1);
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
        String pad = " ".repeat(indent);
        String childPad = " ".repeat(indent + 1);
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

    /**
     * 配置类，定义文件操作的各种限制。
     */
    public record Config(
            long maxFileSize,
            long maxMediaSize,
            int maxWalkDepth,
            int maxWalkEntries,
            int maxSearchResults,
            Set<String> excludedDirs,
            String deleteConfirmToken
    ) {
        /**
         * 默认配置。
         */
        public static Config defaults() {
            return new Config(
                    10 * 1024 * 1024, // 10 MB
                    50 * 1024 * 1024, // 50 MB
                    5, // maxWalkDepth
                    1000, // maxWalkEntries
                    100, // maxSearchResults
                    Set.of(".git", "node_modules", ".idea", "target", ".vscode"),
                    "I_CONFIRM_DELETE"
            );
        }
    }
}
