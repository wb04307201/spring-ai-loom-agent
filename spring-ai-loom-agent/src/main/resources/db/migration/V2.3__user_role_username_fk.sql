-- =============================================================
-- V2.3: user_role.username 加 FK + CASCADE 修 P0.3.3 bug
-- =============================================================
-- 背景:user_role.username 之前没 FK,删除 user 时 user_role 行
-- 留为 orphan(B.2.1 已修 role cascade 类似路径)。本 migration 补
-- 上 FK,让 user 删除自动清理 user_role,跟 role delete 行为一致。
--
-- 安全:已存在的 orphan user_role 行(如果 user 被删了但 user_role
-- 没清)ALTER TABLE 会失败 — 先 cleanup。
-- =============================================================

-- 1) Cleanup pre-existing orphan user_role rows (safety)
DELETE FROM user_role
WHERE username NOT IN (SELECT username FROM user_info);

-- 2) Add FK constraint (CASCADE so user delete also drops user_role)
ALTER TABLE user_role
    ADD CONSTRAINT fk_user_role_user
    FOREIGN KEY (username) REFERENCES user_info(username) ON DELETE CASCADE;
