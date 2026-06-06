package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import org.apache.tika.Tika;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.EmptyResultDataAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DefaultFileTool implements IFileTool {

    private static final String BASE_PATH = ".local/file";

    private final IFile file;

    public DefaultFileTool(IFile file) {
        this.file = file;
    }

    @Tool(description = "根据文件id读取文本文件内容。支持指定 head（仅前N行）或 tail（仅后N行）参数，适合快速查看文件开头或结尾。")
    @Override
    public String readTextFile(
            @ToolParam(description = "文件id") String fileId,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            Path resolved = Path.of(fileRecord.path());
            List<String> lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
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

    @Tool(description = "根据文件id读取图片或音频文件，返回 base64 编码数据和 MIME 类型。")
    @Override
    public String readMediaFile(@ToolParam(description = "文件id") String fileId, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            Path resolved = Path.of(fileRecord.path());
            byte[] data = Files.readAllBytes(resolved);
            String base64 = java.util.Base64.getEncoder().encodeToString(data);
            String mimeType = Files.probeContentType(resolved);
            return "MIME类型：" + mimeType + "\nBase64数据：\n" + base64;
        } catch (IOException e) {
            return "读取媒体文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "根据文件id列表同时读取多个文件的内容，比逐个读取更高效。单个文件读取失败不会影响其他文件。")
    @Override
    public String readMultipleFiles(
            @ToolParam(description = "要读取的文件id列表") List<String> fileIds, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        StringBuilder sb = new StringBuilder();
        for (String fileId : fileIds) {
            FileRecord fileRecord = validateFileExists(fileId, username);
            if (fileRecord == null) {
                sb.append(fileId).append(": 错误 - 文件不存在\n---\n");
                continue;
            }
            try {
                String content = Files.readString(Path.of(fileRecord.path()), StandardCharsets.UTF_8);
                sb.append(fileId).append(" (").append(fileRecord.fileName()).append("):\n").append(content).append("\n---\n");
            } catch (IOException e) {
                sb.append(fileId).append(": 错误 - ").append(e.getMessage()).append("\n---\n");
            }
        }
        return sb.toString();
    }

    @Tool(description = "创建新文件或完全覆盖已有文件内容。path 为相对于用户文件目录的路径（如 notes/todo.txt）。写入新文件后会自动注册到文件管理并返回文件id。如果文件已存在则更新内容并返回原有文件id。")
    @Override
    public String writeFile(
            @ToolParam(description = "相对于用户文件目录的文件路径，如 notes/todo.txt") String path,
            @ToolParam(description = "要写入的文本内容") String content,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            Path userDir = getUserFileDir(username);
            Path resolved = userDir.resolve(path).normalize();
            if (!resolved.startsWith(userDir)) {
                return "错误：路径不能超出用户文件目录范围";
            }
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);

            FileRecord existing = file.getByPath(resolved.toString(), username);

            if (existing != null) {
                file.update(existing.id(), null, null, resolved.toFile().length(), username);
                return "文件已成功覆盖：" + path + "\n文件id：" + existing.id();
            } else {
                String fileId = registerFile(resolved, username);
                return "文件已成功写入：" + path + "\n已自动注册到文件管理，文件id：" + fileId;
            }
        } catch (IOException e) {
            return "写入文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "根据文件id对文件进行基于行的编辑，每次编辑用新内容替换精确匹配的文本序列，返回 git 风格的 diff。")
    @Override
    public String editFile(
            @ToolParam(description = "文件id") String fileId,
            @ToolParam(description = "编辑列表，每个编辑包含 oldText（要替换的文本）和 newText（替换后的文本）") List<Map<String, String>> edits, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            Path resolved = Path.of(fileRecord.path());
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            StringBuilder diff = new StringBuilder();
            diff.append("--- ").append(fileRecord.fileName()).append("\n");
            diff.append("+++ ").append(fileRecord.fileName()).append("\n");

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

            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);
            file.update(fileId, null, null, resolved.toFile().length(), username);
            diff.insert(0, "已应用 ").append(edits.size()).append(" 处编辑到 ").append(fileRecord.fileName()).append("\n\n");
            return diff.toString();
        } catch (IOException e) {
            return "编辑文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "创建新目录或确保目录已存在，支持创建多级嵌套目录，如果目录已存在则静默成功。path 为相对于用户文件目录的路径。")
    @Override
    public String createDirectory(
            @ToolParam(description = "相对于用户文件目录的目录路径，如 notes/2026") String path,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            Path userDir = getUserFileDir(username);
            Path resolved = userDir.resolve(path).normalize();
            if (!resolved.startsWith(userDir)) {
                return "错误：路径不能超出用户文件目录范围";
            }
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

    @Tool(description = "根据文件id移动或重命名文件。移动后自动更新文件管理中的路径和文件名信息。目标路径相对于用户文件目录。")
    @Override
    public String moveFile(
            @ToolParam(description = "要移动的文件id") String fileId,
            @ToolParam(description = "目标路径，相对于用户文件目录") String destination, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            Path resolvedSource = Path.of(fileRecord.path());
            Path userDir = getUserFileDir(username);
            Path resolvedDest = userDir.resolve(destination).normalize();
            if (!resolvedDest.startsWith(userDir)) {
                return "错误：目标路径不能超出用户文件目录范围";
            }
            if (Files.exists(resolvedDest)) {
                return "错误：目标路径已存在 - " + destination;
            }
            // Ensure parent directory exists
            Path parent = resolvedDest.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.move(resolvedSource, resolvedDest, StandardCopyOption.ATOMIC_MOVE);
            file.update(fileId, resolvedDest.toString(), resolvedDest.getFileName().toString(), null, username);
            return "已移动：" + fileRecord.fileName() + " -> " + destination;
        } catch (IOException e) {
            return "移动文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "搜索文件管理中的文件。不传关键词则返回所有文件。支持按文件id精确匹配、按文件名模糊搜索、按路径模糊搜索。")
    @Override
    public String searchFiles(
            @ToolParam(description = "搜索关键词，匹配文件id、文件名或路径。不传则返回所有文件") String keyword,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            LinkedHashMap<String, FileRecord> result = new LinkedHashMap<>();

            if (keyword == null || keyword.isBlank()) {
                for (FileRecord record : file.list(null, username)) {
                    result.put(record.id(), record);
                }
            } else {
                // Try fileId exact match
                try {
                    FileRecord record = file.getById(keyword, username);
                    if (record != null) {
                        result.put(record.id(), record);
                        return formatSearchResults(result);
                    }
                } catch (EmptyResultDataAccessException ignored) {
                }
                // Filename LIKE search
                for (FileRecord record : file.searchByFileName(keyword, username)) {
                    result.put(record.id(), record);
                }
                // Path LIKE search
                for (FileRecord record : file.searchByPath(keyword, username)) {
                    result.put(record.id(), record);
                }
            }

            if (result.isEmpty()) {
                return "未找到匹配的文件";
            }
            return formatSearchResults(result);
        } catch (Exception e) {
            return "搜索文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出当前用户的文件操作目录。写入或创建文件时，路径均相对于此目录。")
    @Override
    public String listAllowedDirectories(ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path userDir = getUserFileDir(username);
        return "用户文件目录：" + userDir;
    }

    @Tool(description = "根据文件id生成原始文件下载URL（WOPI端点）。适用于需要获取文件原始二进制内容的场景，如图片、音频、二进制文件等。返回 [下载:文件名](url) 格式的 Markdown 链接。")
    @Override
    public String downloadFileUrl(@ToolParam(description = "文件id") String fileId, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        String baseUrl = (String) toolContext.getContext().get("baseUrl");
        return "[下载:" + fileRecord.fileName() + "](" + baseUrl + "/spring/ai/loom/file/" + fileId + "/download)";
    }

    @Tool(description = "根据文件id生成文件在线预览URL。适用于需要在浏览器中直接查看文件的场景，支持 PDF、Word、Excel、PPT、图片、Markdown 等格式。返回 [预览:文件名](url) 格式的 Markdown 链接。")
    @Override
    public String viewFileUrl(@ToolParam(description = "文件id") String fileId, ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        String baseUrl = (String) toolContext.getContext().get("baseUrl");
        return "[预览:" + fileRecord.fileName() + "](" + baseUrl + "/file/view/" + fileId + ")";
    }

    // ==================== Helpers ====================

    private Path getUserFileDir(String username) {
        return Paths.get(BASE_PATH, username, "file");
    }

    private FileRecord validateFileExists(String fileId, String username) {
        FileRecord fileRecord;
        try {
            fileRecord = file.getById(fileId, username);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("文件信息不存在，文件id无效");
        }
        if (Files.notExists(Path.of(fileRecord.path()))) {
            file.delete(fileId, username);
            return null;
        }
        return fileRecord;
    }

    private String registerFile(Path filePath, String username) {
        try {
            Tika tika = new Tika();
            String mimeType = tika.detect(filePath.toFile());
            String fileId = UUID.randomUUID().toString();
            file.insert(new FileRecord(
                    fileId,
                    null,
                    filePath.getFileName().toString(),
                    filePath.toFile().length(),
                    LocalDateTime.now(),
                    filePath.toString(),
                    "tool",
                    mimeType
            ), username);
            return fileId;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String formatSearchResults(LinkedHashMap<String, FileRecord> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("找到 %d 个文件:%n%n", results.size()));
        for (FileRecord record : results.values()) {
            sb.append("文件id: ").append(record.id())
                    .append(" | 文件名: ").append(record.fileName())
                    .append(" | 路径: ").append(record.path())
                    .append(" | 大小: ").append(formatSize(record.size()))
                    .append("\n");
        }
        return sb.toString();
    }
}
