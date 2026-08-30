-- =============================================================
-- Role 删除 cascade (B.2.1 修复)
-- =============================================================
-- 历史:`user_role` / `role_mcp` / `role_tool` 三张子表对 `role.code` 都没有 FK 约束,
-- 也没有应用层 cascade。删除 role 时,这三张表的子行变成 dangling 引用,
-- 用户仍能用已删除 role 的授权(实测确认)。
--
-- 修复:
--   1. 加 FK 约束 + ON DELETE CASCADE,DB 层保证
--   2. 应用层 deleteOrThrow 显式 DELETE 子表(防御性 + SQL 可见)
-- 对已运行实例也兼容(只是补加约束,不影响已有数据)。
-- =============================================================

-- 1. user_role.role_code → role.code
ALTER TABLE user_role
    ADD CONSTRAINT fk_user_role_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;

-- 2. role_mcp.role_code → role.code
ALTER TABLE role_mcp
    ADD CONSTRAINT fk_role_mcp_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;

-- 3. role_tool.role_code → role.code
ALTER TABLE role_tool
    ADD CONSTRAINT fk_role_tool_role
    FOREIGN KEY (role_code) REFERENCES role(code) ON DELETE CASCADE;
