-- =============================================================
-- V2.4: 清理 role_tool 表里 6 个 universal 工具的历史授权记录
-- =============================================================
-- 背景:M6 引入 @ToolGroup(defaultGranted=true) 机制,把
--   schedule / subtask / knowledge / time / skill / file
-- 6 个工具标记为"平台默认能力",对所有登录用户可见,与 role_tool
-- RBAC 完全解耦。admin UI 不展示这 6 个工具的可增删入口,数据库
-- 端保留旧行只会带来误导("为什么这个 role 还显示 schedule 已被
-- 授权但我又没法移除它?")。
--
-- 本 migration 一次性 DELETE 这 6 个 group_name 的所有 role_tool
-- 行。后续 INSERT 也由 CapabilityService.defaultGranted 硬约束
-- 不写入;DDL 层不强制阻止(保留 schema 灵活性),但代码路径上
-- 不会再有新增。
--
-- group_name 形式:"tool_" + @ToolGroup value:
--   schedule  → tool_schedule
--   subtask   → tool_subtask
--   knowledge → tool_knowledge
--   time      → tool_time
--   skill     → tool_skill
--   file      → tool_file
--
-- 安全:DELETE 而非 TRUNCATE,只动指定 group_name;如果某些环境
-- 期望保留这些记录做审计,可注释掉本文件后手动跑。
-- =============================================================

DELETE FROM role_tool
WHERE group_name IN (
    'tool_schedule',
    'tool_subtask',
    'tool_knowledge',
    'tool_time',
    'tool_skill',
    'tool_file'
);