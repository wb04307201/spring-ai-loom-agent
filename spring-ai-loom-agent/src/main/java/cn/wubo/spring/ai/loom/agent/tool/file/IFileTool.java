package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

public interface IFileTool extends IEmbedTool {

    String readTextFile(String fileId, Integer head, Integer tail, String gitRelativePath, ToolContext toolContext);

    String readMediaFile(String fileId, ToolContext toolContext, String gitRelativePath);

    String readMultipleFiles(List<String> fileIds, ToolContext toolContext);

    String writeFile(String path, String content, String gitRepoFileId, ToolContext toolContext);

    String editFile(String fileId, List<Map<String, String>> edits, String gitRelativePath, ToolContext toolContext);

    String createDirectory(String path, String gitRepoFileId, ToolContext toolContext);

    String moveFile(String fileId, String destination, String targetGitRepoFileId, ToolContext toolContext);

    String searchFiles(String keyword, String gitRepoFileId, ToolContext toolContext);

    String listAllowedDirectories(ToolContext toolContext);

    String downloadFileUrl(String fileId, ToolContext toolContext);

    String viewFileUrl(String fileId, ToolContext toolContext);
}
