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
import java.util.stream.Stream;

public class DefaultFileTool implements IFileTool {

    private static final String BASE_PATH = ".local/file";
    private static final String GIT_SUBDIR = "git";

    private final IFile file;

    public DefaultFileTool(IFile file) {
        this.file = file;
    }

    @Tool(description = "根据文件id读取文本文件内容。支持指定 head（仅前N行）或 tail（仅后N行）参数，适合快速查看文件开头或结尾。如果文件属于git仓库，传入 gitRelativePath 可读取git项目内的文件。")
    @Override
    public String readTextFile(
            @ToolParam(description = "文件id") String fileId,
            @ToolParam(description = "如果提供，仅输出文件的前 N 行", required = false) Integer head,
            @ToolParam(description = "如果提供，仅输出文件的后 N 行", required = false) Integer tail,
            @ToolParam(description = "如果提供，表示要读取的文件相对于git仓库根目录的路径，此时fileId应为git仓库的文件id", required = false) String gitRelativePath,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolveFilePath(fileId, username, gitRelativePath);
        if (filePath == null) {
            return "文件不存在，已被自动清理";
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

    @Tool(description = "根据文件id读取图片或音频文件，返回 base64 编码数据和 MIME 类型。如果文件属于git仓库，传入 gitRelativePath 可读取git项目内的文件。")
    @Override
    public String readMediaFile(
            @ToolParam(description = "文件id") String fileId,
            ToolContext toolContext,
            @ToolParam(description = "如果提供，表示要读取的文件相对于git仓库根目录的路径，此时fileId应为git仓库的文件id", required = false) String gitRelativePath) {
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolveFilePath(fileId, username, gitRelativePath);
        if (filePath == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            byte[] data = Files.readAllBytes(filePath);
            String base64 = java.util.Base64.getEncoder().encodeToString(data);
            String mimeType = Files.probeContentType(filePath);
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

    @Tool(description = "创建新文件或完全覆盖已有文件内容。path 为相对于用户文件目录的路径（如 notes/todo.txt）。写入新文件后会自动注册到文件管理并返回文件id。如果文件已存在则更新内容并返回原有文件id。如果指定 gitRepoFileId，则在对应git仓库目录下创建文件。")
    @Override
    public String writeFile(
            @ToolParam(description = "相对于目标目录的文件路径，如 notes/todo.txt 或 src/main.java") String path,
            @ToolParam(description = "要写入的文本内容") String content,
            @ToolParam(description = "如果提供，表示git仓库的文件id，文件将写入该git仓库目录下", required = false) String gitRepoFileId,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            Path targetDir = resolveWriteBaseDir(username, gitRepoFileId);
            if (targetDir == null) return "错误：指定的git仓库不存在";
            Path resolved = targetDir.resolve(path).normalize();
            if (!resolved.startsWith(targetDir)) {
                return "错误：路径不能超出目标目录范围";
            }
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);

            if (gitRepoFileId != null) {
                return "文件已成功写入git仓库：" + path + "\n（git仓库文件不单独注册到文件管理）";
            }

            FileRecord existing = file.getByExactPath(resolved.toString(), username);

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

    @Tool(description = "根据文件id对文件进行基于行的编辑，每次编辑用新内容替换精确匹配的文本序列，返回 git 风格的 diff。如果文件属于git仓库，传入 gitRelativePath 可编辑git项目内的文件。")
    @Override
    public String editFile(
            @ToolParam(description = "文件id") String fileId,
            @ToolParam(description = "编辑列表，每个编辑包含 oldText（要替换的文本）和 newText（替换后的文本）") List<Map<String, String>> edits,
            @ToolParam(description = "如果提供，表示要编辑的文件相对于git仓库根目录的路径，此时fileId应为git仓库的文件id", required = false) String gitRelativePath,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path filePath = resolveFilePath(fileId, username, gitRelativePath);
        if (filePath == null) {
            return "文件不存在，已被自动清理";
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

            // 更新数据库记录（仅普通文件有独立记录）
            if (gitRelativePath == null) {
                FileRecord fileRecord = file.getById(fileId, username);
                file.update(fileId, null, null, filePath.toFile().length(), username);
            }

            diff.insert(0, "已应用 ").append(edits.size()).append(" 处编辑到 ").append(fileName).append("\n\n");
            return diff.toString();
        } catch (IOException e) {
            return "编辑文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "创建新目录或确保目录已存在，支持创建多级嵌套目录，如果目录已存在则静默成功。如果指定 gitRepoFileId，则在对应git仓库目录下创建目录。")
    @Override
    public String createDirectory(
            @ToolParam(description = "相对于目标目录的目录路径，如 notes/2026 或 src/main/java") String path,
            @ToolParam(description = "如果提供，表示git仓库的文件id，目录将创建在该git仓库目录下", required = false) String gitRepoFileId,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            Path targetDir = resolveWriteBaseDir(username, gitRepoFileId);
            if (targetDir == null) return "错误：指定的git仓库不存在";
            Path resolved = targetDir.resolve(path).normalize();
            if (!resolved.startsWith(targetDir)) {
                return "错误：路径不能超出目标目录范围";
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

    @Tool(description = "根据文件id移动或重命名文件。移动后自动更新文件管理中的路径和文件名信息。如果指定 targetGitRepoFileId，目标路径将相对于该git仓库目录。")
    @Override
    public String moveFile(
            @ToolParam(description = "要移动的文件id") String fileId,
            @ToolParam(description = "目标路径，相对于目标目录") String destination,
            @ToolParam(description = "如果提供，表示目标git仓库的文件id，目标路径将相对于该git仓库", required = false) String targetGitRepoFileId,
            ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) {
            return "文件不存在，已被自动清理";
        }
        try {
            Path resolvedSource = Path.of(fileRecord.path());
            Path targetDir = resolveWriteBaseDir(username, targetGitRepoFileId);
            if (targetDir == null) return "错误：指定的目标git仓库不存在";
            Path resolvedDest = targetDir.resolve(destination).normalize();
            if (!resolvedDest.startsWith(targetDir)) {
                return "错误：目标路径不能超出目标目录范围";
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

    @Tool(description = "搜索文件管理中的文件，同时扫描git仓库目录。不传关键词则返回所有文件。支持按文件id精确匹配、按文件名模糊搜索、按路径模糊搜索。")
    @Override
    public String searchFiles(
            @ToolParam(description = "搜索关键词，匹配文件id、文件名或路径。不传则返回所有文件") String keyword,
            @ToolParam(description = "如果提供，仅搜索该git仓库目录内的文件", required = false) String gitRepoFileId,
            ToolContext toolContext) {
        try {
            String username = (String) toolContext.getContext().get("username");
            LinkedHashMap<String, FileRecord> result = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder();

            if (keyword == null || keyword.isBlank()) {
                // 列出所有普通文件
                for (FileRecord record : file.list(null, username)) {
                    result.put(record.id(), record);
                }
            } else {
                // Try fileId exact match (普通文件)
                try {
                    FileRecord record = file.getById(keyword, username);
                    if (record != null) {
                        result.put(record.id(), record);
                    }
                } catch (EmptyResultDataAccessException ignored) {
                }
                if (result.isEmpty()) {
                    for (FileRecord record : file.search(keyword, username)) {
                        result.put(record.id(), record);
                    }
                }
            }

            // 扫描git仓库目录
            scanGitRepos(username, gitRepoFileId, keyword, result);

            if (result.isEmpty()) {
                return "未找到匹配的文件";
            }

            sb.append(String.format("找到 %d 个文件:%n%n", result.size()));
            for (FileRecord record : result.values()) {
                sb.append("文件id: ").append(record.id())
                        .append(" | 文件名: ").append(record.fileName())
                        .append(" | 路径: ").append(record.path())
                        .append(" | 大小: ").append(formatSize(record.size()))
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "搜索文件失败：" + e.getMessage();
        }
    }

    @Tool(description = "列出当前用户的文件操作目录。写入或创建文件时，路径均相对于此目录。同时列出git仓库目录。")
    @Override
    public String listAllowedDirectories(ToolContext toolContext) {
        String username = (String) toolContext.getContext().get("username");
        Path userDir = getUserFileDir(username);
        Path gitDir = Paths.get(BASE_PATH, username, GIT_SUBDIR);

        StringBuilder sb = new StringBuilder();
        sb.append("用户文件目录：").append(userDir).append("\n");
        sb.append("git仓库目录：").append(gitDir).append("\n");

        // 列出已注册的git仓库
        try {
            for (FileRecord record : file.list(null, username)) {
                if ("git".equals(record.usage())) {
                    sb.append("  git仓库: ").append(record.fileName())
                            .append(" (id: ").append(record.id()).append(")")
                            .append("\n");
                }
            }
        } catch (Exception ignored) {
        }

        return sb.toString();
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
        return "[下载:" + fileRecord.fileName() + "](" + baseUrl + "/wopi/files/" + fileId + "/contents)";
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

    /** 根据git仓库fileId解析仓库根路径 */
    private FileRecord resolveGitRepo(String gitRepoFileId, String username) {
        if (gitRepoFileId == null) return null;
        try {
            FileRecord record = file.getById(gitRepoFileId, username);
            if (record != null && "git".equals(record.usage())) {
                return record;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析文件写入的基准目录。有gitRepoFileId则用git仓库根目录，否则用用户普通文件目录 */
    private Path resolveWriteBaseDir(String username, String gitRepoFileId) {
        if (gitRepoFileId != null) {
            FileRecord repo = resolveGitRepo(gitRepoFileId, username);
            if (repo == null) return null;
            Path repoDir = Path.of(repo.path());
            if (Files.notExists(repoDir)) return null;
            return repoDir;
        }
        return getUserFileDir(username);
    }

    /** 解析要读取/编辑的文件的实际路径。有gitRelativePath则从git仓库解析，否则从fileId解析 */
    private Path resolveFilePath(String fileId, String username, String gitRelativePath) {
        if (gitRelativePath != null) {
            FileRecord repo = resolveGitRepo(fileId, username);
            if (repo == null) return null;
            Path repoDir = Path.of(repo.path());
            return repoDir.resolve(gitRelativePath).normalize();
        }
        FileRecord fileRecord = validateFileExists(fileId, username);
        if (fileRecord == null) return null;
        return Path.of(fileRecord.path());
    }

    /** 扫描git仓库目录，将实际文件加入搜索结果 */
    private void scanGitRepos(String username, String gitRepoFileId, String keyword, LinkedHashMap<String, FileRecord> result) throws IOException {
        List<FileRecord> repos;
        if (gitRepoFileId != null) {
            FileRecord repo = resolveGitRepo(gitRepoFileId, username);
            if (repo == null) return;
            repos = List.of(repo);
        } else {
            repos = file.list(null, username).stream()
                    .filter(r -> "git".equals(r.usage()))
                    .toList();
        }

        for (FileRecord repo : repos) {
            Path repoDir = Path.of(repo.path());
            if (!Files.exists(repoDir)) continue;

            try (Stream<Path> walk = Files.walk(repoDir)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    String fileName = file.getFileName().toString();
                    if (keyword != null && !keyword.isBlank()) {
                        if (!fileName.toLowerCase().contains(keyword.toLowerCase())
                                && !file.toString().toLowerCase().contains(keyword.toLowerCase())) {
                            return;
                        }
                    }
                    // 使用 repoId + relativePath 作为合成key
                    String key = repo.id() + ":" + repoDir.relativize(file).toString();
                    if (!result.containsKey(key)) {
                        result.put(key, new FileRecord(
                                repo.id(),
                                null,
                                repoDir.relativize(file).toString(),
                                file.toFile().length(),
                                LocalDateTime.now(),
                                repoDir.relativize(file).toString(),
                                "git",
                                null
                        ));
                    }
                });
            }
        }
    }

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
}
