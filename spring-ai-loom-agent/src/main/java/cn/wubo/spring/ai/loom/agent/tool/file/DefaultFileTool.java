package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import org.apache.tika.Tika;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

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

public class DefaultFileTool implements IFileTool {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IFile file;
    private final String fileBasePath;

    public DefaultFileTool(IFile file, String fileBasePath) {
        this.file = file;
        this.fileBasePath = fileBasePath;
    }

    // ==================== Read operations ====================

    @Tool(description = "读取本地文件内容为文本。path 为相对于用户文件目录的路径。支持 head（仅前N行）或 tail（仅后N行）参数。")
    @Override
    public String readTextFile(
            @ToolParam(description = "相对于用户文件目录的文件路径") String path,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        if (!Files.isRegularFile(filePath)) {
            return "路径不是文件：" + path;
        }
        try {
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
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        if (!Files.isRegularFile(filePath)) {
            return "路径不是文件：" + path;
        }
        try {
            byte[] data = Files.readAllBytes(filePath);
            String base64 = Base64.getEncoder().encodeToString(data);
            String mimeType = Files.probeContentType(filePath);
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
        String username = (String) toolContext.getContext().get("username");
        StringBuilder sb = new StringBuilder();
        for (String path : paths) {
            Path filePath = resolvePath(path, username);
            if (!Files.exists(filePath)) {
                sb.append(path).append(": 错误 - 文件不存在\n---\n");
                continue;
            }
            try {
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
        try {
            String username = (String) toolContext.getContext().get("username");
            Path resolved = resolvePath(path, username);
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return "文件已写入：" + path;
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
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
        if (!Files.exists(filePath)) {
            return "文件不存在：" + path;
        }
        String fileName = filePath.getFileName().toString();
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            StringBuilder diff = new StringBuilder();
            diff.append("--- ").append(fileName).append("\n");
            diff.append("+++ ").append(fileName).append("\n");

            String newContent = content;
            for (Map<String, String> edit : edits) {
                String oldText = edit.get("oldText");
                String newText = edit.get("newText");
                if (oldText == null || newText == null) {
                    return "错误：每个编辑必须包含 oldText 和 newText 字段";
                }
                if (!newContent.contains(oldText)) {
                    return "错误：在文件中未找到文本 '" + oldText + "'";
                }
                newContent = newContent.replace(oldText, newText);

                diff.append("@@ @@\n");
                diff.append("-").append(oldText.replace("\n", "\\n")).append("\n");
                diff.append("+").append(newText.replace("\n", "\\n")).append("\n");
            }

            Files.writeString(filePath, newContent, StandardCharsets.UTF_8);

            diff.insert(0, "已应用 ").append(edits.size()).append(" 处编辑到 ").append(fileName).append("\n\n");
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
        try {
            String username = (String) toolContext.getContext().get("username");
            Path resolved = resolvePath(path, username);
            if (Files.exists(resolved)) {
                if (Files.isDirectory(resolved)) {
                    return "目录已存在：" + path;
                }
                return "错误：路径已存在且不是目录 - " + path;
            }
            Files.createDirectories(resolved);
            return "目录已创建：" + path;
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
        String username = (String) toolContext.getContext().get("username");
        try {
            Path resolvedSource = resolvePath(source, username);
            Path resolvedDest = resolvePath(destination, username);
            if (!Files.exists(resolvedSource)) {
                return "源文件不存在：" + source;
            }
            if (Files.exists(resolvedDest)) {
                return "错误：目标路径已存在 - " + destination;
            }
            Path parent = resolvedDest.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.move(resolvedSource, resolvedDest, StandardCopyOption.ATOMIC_MOVE);
            return "已移动：" + source + " -> " + destination;
        } catch (IOException e) {
            return "移动文件失败：" + e.getMessage();
        }
    }

    // ==================== Search ====================

    @Tool(description = "在用户文件目录中递归搜索文件。pattern 为 glob 风格，如 '*.txt' 或 '**/*.java'。不传则返回所有文件。")
    @Override
    public String searchFiles(
            @ToolParam(description = "搜索模式（glob风格），如 '*.txt' 或 '**/*.java'。不传则返回所有文件", required = false) String pattern,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            Path baseDir = getUserFileDir(username);
            if (!Files.exists(baseDir)) {
                return "用户文件目录不存在";
            }

            PathMatcher matcher = (pattern != null && !pattern.isBlank())
                    ? baseDir.getFileSystem().getPathMatcher("glob:" + pattern)
                    : null;

            StringBuilder sb = new StringBuilder();
            int count = 0;
            try (Stream<Path> walk = Files.walk(baseDir)) {
                var filtered = walk.filter(Files::isRegularFile);
                if (matcher != null) {
                    filtered = filtered.filter(p -> matcher.matches(baseDir.relativize(p)));
                }
                var files = filtered.toList();
                count = files.size();

                if (count == 0) {
                    return "未找到匹配的文件";
                }

                sb.append(String.format("找到 %d 个文件:%n%n", count));
                for (Path f : files) {
                    String relPath = baseDir.relativize(f).toString();
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(f, BasicFileAttributes.class);
                        sb.append("路径: ").append(relPath)
                                .append(" | 大小: ").append(formatSize(attrs.size()))
                                .append(" | 修改: ").append(formatInstant(attrs.lastModifiedTime().toInstant()))
                                .append("\n");
                    } catch (IOException e) {
                        sb.append("路径: ").append(relPath).append("\n");
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索文件失败：" + e.getMessage();
        }
    }

    // ==================== List directories ====================

    @Tool(description = "列出当前用户的文件操作目录。写入或创建文件时，路径均相对于此目录。")
    @Override
    public String listAllowedDirectories(ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path userDir = getUserFileDir(username);
        return "用户文件目录：" + userDir + "\n\n" +
                "说明：所有文件操作的路径参数均相对于此目录。\n" +
                "例如：read_text_file 的 path 参数 'notes/todo.txt' 实际读取 '" + userDir.resolve("notes/todo.txt") + "'。";
    }

    @Tool(description = "列出目录内容。区分文件 [FILE] 和目录 [DIR]。支持 depth 参数控制递归深度。")
    @Override
    public String listDirectory(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串列出根目录") String path,
            @ToolParam(description = "递归深度（默认1，仅直接子项）", required = false) Integer depth,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path dirPath = resolvePath(path.isEmpty() ? "." : path, username);
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            int d = (depth != null && depth > 0) ? depth : 1;
            StringBuilder sb = new StringBuilder();
            listDirRecursive(dirPath, "", d, sb);
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
        String username = (String) toolContext.getContext().get("username");
        Path dirPath = resolvePath(path.isEmpty() ? "." : path, username);
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> stream = Files.list(dirPath)) {
                var items = stream.sorted(Comparator.comparing(p -> Files.isDirectory(p) ? 0 : 1)).toList();
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

    @Tool(description = "获取目录的递归树视图，返回 JSON 格式结构。")
    @Override
    public String directoryTree(
            @ToolParam(description = "相对于用户文件目录的目录路径，空字符串从根目录开始") String path,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path dirPath = resolvePath(path.isEmpty() ? "." : path, username);
        if (!Files.exists(dirPath)) {
            return "目录不存在：" + path;
        }
        if (!Files.isDirectory(dirPath)) {
            return "路径不是目录：" + path;
        }
        try {
            Map<String, Object> tree = buildDirectoryTree(dirPath);
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
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
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
                try (var lines = Files.lines(filePath, StandardCharsets.UTF_8)) {
                    long lineCount = lines.count();
                    sb.append("行数: ").append(lineCount).append("\n");
                } catch (IOException e) {
                    // Ignore
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
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
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
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolvePath(path, username);
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
        String url = baseUrl + "/file/view/" + fileId;
        String fileName = filePath.getFileName().toString();
        return "文件名:" + fileName + "\n" +
                "预览链接:" + url + "\n" +
                "markdown格式:[预览:" + fileName + "](" + url + ")" + "\n";
    }

    // ==================== Helpers ====================

    private Path resolvePath(String path, String username) {
        Path base = getUserFileDir(username);
        Path resolved;
        if (path == null || path.isEmpty() || ".".equals(path)) {
            resolved = base;
        } else {
            resolved = base.resolve(path).normalize();
        }
        if (!resolved.startsWith(base)) {
            throw new SecurityException("路径不能超出用户文件目录：" + base);
        }
        return resolved;
    }

    private Path getUserFileDir(String username) {
        return Paths.get(fileBasePath, username);
    }

    private String getOrCreateFileId(Path filePath, String username) {
        try {
            String pathStr = filePath.toString();
            FileRecord existing = file.getByExactPath(pathStr, username);
            if (existing != null) return existing.id();

            Tika tika = new Tika();
            String mimeType = tika.detect(filePath.toFile());
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

    private void listDirRecursive(Path dir, String indent, int depth, StringBuilder sb) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            var items = stream.sorted(Comparator.comparing(p -> Files.isDirectory(p) ? 0 : 1)).toList();
            for (Path item : items) {
                String name = item.getFileName().toString();
                if (Files.isDirectory(item)) {
                    sb.append(indent).append("[DIR]  ").append(name).append("\n");
                    if (depth > 1) {
                        listDirRecursive(item, indent + "  ", depth - 1, sb);
                    }
                } else {
                    long size = Files.size(item);
                    sb.append(indent).append("[FILE] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDirectoryTree(Path dir) throws IOException {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", dir.getFileName() != null ? dir.getFileName().toString() : ".");
        node.put("type", "directory");

        List<Map<String, Object>> children = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            var sorted = stream.sorted(Comparator.comparing(p -> Files.isDirectory(p) ? 0 : 1)).toList();
            for (Path item : sorted) {
                if (Files.isDirectory(item)) {
                    children.add(buildDirectoryTree(item));
                } else {
                    Map<String, Object> fileNode = new LinkedHashMap<>();
                    fileNode.put("name", item.getFileName().toString());
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
            sb.append(childPad).append("\"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof String s) {
                sb.append("\"").append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            } else if (value instanceof Number n) {
                sb.append(n);
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
                sb.append("\"").append(s).append("\"");
            } else {
                sb.append(item);
            }
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(pad).append("]");
        return sb.toString();
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
