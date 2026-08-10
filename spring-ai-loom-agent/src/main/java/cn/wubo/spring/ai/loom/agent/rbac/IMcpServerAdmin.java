package cn.wubo.spring.ai.loom.agent.rbac;

import cn.wubo.spring.ai.loom.agent.model.McpServerInfo;
import cn.wubo.spring.ai.loom.agent.model.McpSystemView;
import cn.wubo.spring.ai.loom.agent.model.McpToolInfo;

import java.util.List;

public interface IMcpServerAdmin {

    List<McpServerInfo> listAll();

    /**
     * 系统视图：合并 SDK 实时 mcp + DB 元数据（mcps.html 和 roles.html 都用这个）
     */
    List<McpSystemView> listSystem();

    McpServerInfo update(String name, String title, String description);

    List<McpToolInfo> listTools(String mcpName);

    McpToolInfo updateTool(Long toolId, String description);

    /**
     * id=null 时 INSERT 新记录，返回带 id 的对象
     */
    McpToolInfo upsertTool(Long toolId, String mcpName, String name, String description);
}
