package cn.wubo.spring.ai.loom.agent.tool.file;

import cn.wubo.spring.ai.loom.agent.tool.IEmbedTool;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

public interface IFileTool extends IEmbedTool {

    String readTextFile(String path, Integer head, Integer tail, ToolContext toolContext);

    String readMediaFile(String path, ToolContext toolContext);

    String readMultipleFiles(List<String> paths, ToolContext toolContext);

    String writeFile(String path, String content, ToolContext toolContext);

    String editFile(String path, List<Map<String, String>> edits, ToolContext toolContext);

    String createDirectory(String path, ToolContext toolContext);

    String moveFile(String source, String destination, ToolContext toolContext);

    String searchFiles(String pattern, ToolContext toolContext);

    String listAllowedDirectories(ToolContext toolContext);

    String listDirectory(String path, Integer depth, ToolContext toolContext);

    String listDirectoryWithSizes(String path, ToolContext toolContext);

    String directoryTree(String path, ToolContext toolContext);

    String getFileInfo(String path, ToolContext toolContext);

    String downloadFileUrl(String path, ToolContext toolContext);

    String viewFileUrl(String path, ToolContext toolContext);

    String deleteFileOrDirectory(String path, String confirm, ToolContext toolContext);
}
