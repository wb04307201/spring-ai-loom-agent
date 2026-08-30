-- =============================================================
-- 本地工具组 RBAC：role_tool
-- =============================================================
-- 镜像 role_mcp 的设计：
--   - role_code    : 业务角色代码（与 role 表 code 对齐）
--   - group_name   : 本地工具组 slug（@ToolGroup 注解值 + "tool_" 前缀，
--                    例如 IFileTool(@ToolGroup("file")) → group_name = "tool_file"）
--   - default_enabled: TRUE → 用户的聊天面板 checkbox 默认勾选（用户可手动取消）
--   - sort_order   : 角色授权列表的展示顺序
--
-- 设计要点：
--   1. group_name 用 VARCHAR(64) 容纳 "tool_" 前缀（最多 32+1+32）。
--   2. 镜像 role_mcp 的 default_enabled 默认 TRUE，因为 admin 创建角色授权时
--      大多数场景是"给组里人放开这几个工具"；如有需要可在授权时手动设 false。
--   3. 不 seed 默认 admin 授权（admin 账号首次部署后必须手动进控制台授权）—— Q12 决定。
--   4. 不创建 user_tool_enabled 表：用户级 checkbox 状态由前端 state 持有，
--      与现有 MCP 模式（无 user_mcp_enabled 表）一致。
-- =============================================================


CREATE TABLE role_tool
(
    role_code       VARCHAR(32)  NOT NULL,
    group_name      VARCHAR(64)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    default_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (role_code, group_name)
);
CREATE INDEX idx_role_tool_code ON role_tool(role_code, sort_order);


-- =============================================================
-- 说明：本 migration 不修改现有 mcp_server / role_mcp / mcp_tool 表。
-- 9 个本地 I*Tool 接口已通过 @ToolGroup 注解声明 group_name；
-- CapabilityService 把 group_name 前缀 "tool_" 后存入本表。
-- =============================================================