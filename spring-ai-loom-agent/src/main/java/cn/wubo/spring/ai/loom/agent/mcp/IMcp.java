package cn.wubo.spring.ai.loom.agent.mcp;

import cn.wubo.spring.ai.loom.agent.model.McpRecord;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

public interface IMcp {

 List<McpRecord> mcps();

 /**
 * 按用户角色过滤 mcp：合并用户所有角色的 mcp 列表，与 requestedMcps 求交集。
 * 用户选了不在自己角色内的 mcp：忽略（warn log）。
 * admin 用户返回所有活跃 mcp。
 */
 ToolCallbackProvider getVisibleToolCallbackProvider(String username, List<String> requestedMcps);
}
