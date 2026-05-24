package cn.wubo.spring.ai.loom.agent.tool;

import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

public interface IEmbedTool {

    String getCurrentTime(String timezone);

    String convertTime(String sourceTimezone, String time, String targetTimezone);

    String skillContents(ToolContext toolContext);

    String getSkill(String name,ToolContext toolContext);

    String readTextFile(String fileId, Integer head, Integer tail, ToolContext toolContext);

    String readMediaFile(String fileId, ToolContext toolContext);

    String readMultipleFiles(List<String> fileIds, ToolContext toolContext);

    String writeFile(String path, String content, ToolContext toolContext);

    String editFile(String fileId, List<Map<String, String>> edits, ToolContext toolContext);

    String createDirectory(String path);

    String moveFile(String fileId, String destination, ToolContext toolContext);

    String searchFiles(String keyword, ToolContext toolContext);

    String listAllowedDirectories();

    String downloadFileUrl(String fileId, ToolContext toolContext);

    String viewFileUrl(String fileId, ToolContext toolContext);
}
