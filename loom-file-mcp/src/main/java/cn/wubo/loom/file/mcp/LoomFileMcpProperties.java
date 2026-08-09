package cn.wubo.loom.file.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

/**
 * Loom File MCP 配置属性。
 */
@Data
@ConfigurationProperties(prefix = "loom.file.mcp")
public class LoomFileMcpProperties {

 /**
 * 文件操作的基础目录路径。
 */
 private String basePath = ".local/file";

 /**
 * 文本文件最大大小（字节）。
 */
 private long maxFileSize = 10 * 1024 * 1024; // 10 MB

 /**
 * 媒体文件最大大小（字节）。
 */
 private long maxMediaSize = 50 * 1024 * 1024; // 50 MB

 /**
 * 目录遍历最大深度。
 */
 private int maxWalkDepth = 5;

 /**
 * 目录遍历最大条目数。
 */
 private int maxWalkEntries = 1000;

 /**
 * 搜索结果最大数量。
 */
 private int maxSearchResults = 100;

 /**
 * 排除的目录名称列表。
 */
 private Set<String> excludedDirs = new HashSet<>(Set.of(
 ".git", "node_modules", ".idea", "target", ".vscode"
 ));

 /**
 * 删除操作的确认令牌。
 */
 private String deleteConfirmToken = "I_CONFIRM_DELETE";

}
