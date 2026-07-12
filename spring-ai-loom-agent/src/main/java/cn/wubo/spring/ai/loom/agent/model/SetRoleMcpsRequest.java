package cn.wubo.spring.ai.loom.agent.model;

import cn.wubo.spring.ai.loom.agent.rbac.IRoleService;
import java.util.List;

/** 角色授权 mcp 时传 [{name, defaultEnabled}, ...]（defaultEnabled 表示聊天界面默认勾选） */
public record SetRoleMcpsRequest(List<IRoleService.RoleMcpItem> items) {
}
