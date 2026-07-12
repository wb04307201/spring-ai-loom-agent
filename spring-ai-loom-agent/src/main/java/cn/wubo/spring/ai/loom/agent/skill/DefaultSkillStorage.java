package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 user_skill 表 + 角色授权自动同步 + admin 特权视图（市场 union）。
 * 旧 yml 嵌入的 skill 完全废弃（已 seed 进 market_skill）。
 */
@Component
public class DefaultSkillStorage implements ISkillStorage {

    private final JdbcTemplate jdbcTemplate;
    private final ResourceLoader resourceLoader;
    private final ISkillRoleAdmin roleAdmin;
    private final IUser user;

    public DefaultSkillStorage(JdbcTemplate jdbcTemplate,
                               ResourceLoader resourceLoader,
                               ISkillRoleAdmin roleAdmin,
                               IUser user) {
        this.jdbcTemplate = jdbcTemplate;
        this.resourceLoader = resourceLoader;
        this.roleAdmin = roleAdmin;
        this.user = user;
    }

    /* ===================== 公共查询 ===================== */

    @Override
    public List<SkillRecord> list(String username) {
        // 1) 同步角色授权的 Skill（不会重复插入；locked=true）
        sync(username);
        // 2) 取 user_skill
        List<SkillRecord> out = new ArrayList<>(queryUserSkills(username));
        // 3) admin 特权视图：合并市场 APPROVED + 自己 PENDING
        if (user.isAdmin(username)) {
            for (SkillRecord m : queryAdminUnionView(username)) {
                boolean dup = out.stream().anyMatch(s -> s.name().equals(m.name()));
                if (!dup) out.add(m);
            }
        }
        return out;
    }

    @Override
    public SkillRecord get(String name, String username) {
        sync(username);
        List<SkillRecord> userSkills = queryUserSkills(username);
        for (SkillRecord s : userSkills) {
            if (s.name().equals(name)) return s;
        }
        // admin fallback：市场里查
        if (user.isAdmin(username)) {
            for (SkillRecord s : queryAdminUnionView(username)) {
                if (s.name().equals(name)) return s;
            }
        }
        throw new LoomAgentRuntimeException("Skill 不存在或无权限: " + name);
    }

    @Override
    public List<SkillRecord> listForAdminUnionView(String username) {
        if (!user.isAdmin(username)) return List.of();
        return queryAdminUnionView(username);
    }

    /* ===================== CRUD ===================== */

    @Override
    public int save(SkillRecord skill, String username) {
        if (skill.name() == null || skill.name().isBlank()) {
            throw new LoomAgentRuntimeException("name 不能为空");
        }
        if (skill.content() == null) {
            throw new LoomAgentRuntimeException("content 不能为空");
        }
        // 已存在就 update（同 name），否则 insert；locked 的不让改
        UserSkill existing = findUserSkill(username, skill.name());
        if (existing != null) {
            if (existing.locked()) {
                throw new LoomAgentRuntimeException("该 Skill 已被角色授权锁定，不能修改");
            }
            return jdbcTemplate.update(
                    "UPDATE user_skill SET description = ?, content = ?, default_loaded = ?, " +
                            "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    skill.description(), skill.content(), skill.load(), existing.id());
        }
        return jdbcTemplate.update(
                "INSERT INTO user_skill (username, name, description, content, source, default_loaded, locked) " +
                        "VALUES (?, ?, ?, ?, 'USER_CREATED', ?, FALSE)",
                username, skill.name(), skill.description(), skill.content(), skill.load());
    }

    @Override
    public int remove(String name, String username) {
        UserSkill existing = findUserSkill(username, name);
        if (existing == null) {
            throw new LoomAgentRuntimeException("Skill 不存在: " + name);
        }
        if (existing.locked()) {
            throw new LoomAgentRuntimeException("该 Skill 已被角色授权锁定，不能删除");
        }
        return jdbcTemplate.update("DELETE FROM user_skill WHERE id = ?", existing.id());
    }

    @Override
    public int patch(String name, String username, UserSkillPatchRequest req) {
        UserSkill existing = findUserSkill(username, name);
        if (existing == null) {
            throw new LoomAgentRuntimeException("Skill 不存在: " + name);
        }
        if (existing.locked()) {
            throw new LoomAgentRuntimeException("该 Skill 已被角色授权锁定，不能修改");
        }
        String desc = req.description() == null ? existing.description() : req.description();
        boolean def = req.defaultLoaded() == null ? existing.defaultLoaded() : req.defaultLoaded();
        return jdbcTemplate.update(
                "UPDATE user_skill SET description = ?, default_loaded = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                desc, def, existing.id());
    }

    /* ===================== 同步（角色授权 → user_skill） ===================== */

    @Override
    public void sync(String username) {
        // 遍历用户角色，对每条 role_skill 在 user_skill upsert 一条 ROLE_GRANTED
        List<String> roleCodes = userRoles(username);
        // 用 username 锁（粗粒度）避免并发重入
        synchronized (("sync:" + username).intern()) {
            for (String roleCode : roleCodes) {
                syncRoleSkills(username, roleCode);
            }
        }
    }

    private void syncRoleSkills(String username, String roleCode) {
        // 查 role_skill JOIN market_skill
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.market_skill_id AS mid, r.default_loaded AS def, m.id, m.name, m.description, m.content, m.version " +
                        "FROM role_skill r JOIN market_skill m ON r.market_skill_id = m.id " +
                        "WHERE r.role_code = ?", roleCode);
        for (Map<String, Object> r : rows) {
            Long mid = ((Number) r.get("mid")).longValue();
            String name = (String) r.get("name");
            String desc = (String) r.get("description");
            String content = SkillContentResolver.resolve((String) r.get("content"), resourceLoader);
            String version = (String) r.get("version");
            boolean def = (Boolean) r.get("def");

            // 同 (username, name) 的 user_skill 已存在？
            UserSkill existing = findUserSkill(username, name);
            if (existing != null) {
                if (existing.locked() && existing.marketSkillId() != null && existing.marketSkillId().equals(mid)) {
                    // 已是同一 ROLE_GRANTED 实例，更新 default_loaded（用户在 locked=true 时不能改，但 sync 可以同步）
                    jdbcTemplate.update(
                            "UPDATE user_skill SET default_loaded = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                            def, existing.id());
                    continue;
                }
                if (existing.locked()) {
                    // 名字冲突（locked 但 market_skill_id 不同）→ 加后缀
                    name = name + "_" + roleCode;
                    existing = findUserSkill(username, name);
                }
            }
            if (existing != null) {
                jdbcTemplate.update(
                        "UPDATE user_skill SET source = 'ROLE_GRANTED', market_skill_id = ?, " +
                                "market_version = ?, content = ?, description = ?, " +
                                "default_loaded = ?, locked = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        mid, version, content, desc, def, existing.id());
            } else {
                jdbcTemplate.update(
                        "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, " +
                                "market_version, default_loaded, locked) " +
                                "VALUES (?, ?, ?, ?, 'ROLE_GRANTED', ?, ?, ?, TRUE)",
                        username, name, desc, content, mid, version, def);
            }
        }
    }

    /* ===================== 内部 ===================== */

    private List<String> userRoles(String username) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT role_code FROM user_role WHERE username = ?", String.class, username);
        } catch (Exception e) {
            return List.of();
        }
    }

    private UserSkill findUserSkill(String username, String name) {
        List<UserSkill> list = jdbcTemplate.query(
                "SELECT * FROM user_skill WHERE username = ? AND name = ?",
                (rs, n) -> mapUserSkill(rs),
                username, name);
        return list.isEmpty() ? null : list.get(0);
    }

    private List<SkillRecord> queryUserSkills(String username) {
        List<UserSkill> rows = jdbcTemplate.query(
                "SELECT * FROM user_skill WHERE username = ? ORDER BY source, name",
                (rs, n) -> mapUserSkill(rs), username);
        List<SkillRecord> out = new ArrayList<>(rows.size());
        for (UserSkill u : rows) {
            out.add(new SkillRecord(u.name(), u.description(), u.defaultLoaded(),
                    SkillContentResolver.resolve(u.content(), resourceLoader), u.source()));
        }
        return out;
    }

    /**
     * admin union view：所有 APPROVED + 自己的 PENDING（不写 user_skill）。
     * 名称冲突 user_skill 优先（已在 list() 里做去重）。
     */
    private List<SkillRecord> queryAdminUnionView(String username) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT name, description, content, version, status FROM market_skill " +
                        "WHERE status = 'APPROVED' OR (status = 'PENDING' AND author = ?) " +
                        "ORDER BY name",
                username);
        List<SkillRecord> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String name = (String) r.get("name");
            String desc = (String) r.get("description");
            String content = SkillContentResolver.resolve((String) r.get("content"), resourceLoader);
            out.add(new SkillRecord(name, desc, true, content, "MARKET_VIEW"));
        }
        return out;
    }

    private UserSkill mapUserSkill(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserSkill(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("content"),
                rs.getString("source"),
                (Long) rs.getObject("market_skill_id"),
                rs.getString("market_version"),
                rs.getBoolean("default_loaded"),
                rs.getBoolean("locked"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
