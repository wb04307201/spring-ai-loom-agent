package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.RoleSkillItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultSkillRoleAdmin implements ISkillRoleAdmin {

    private final JdbcTemplate jdbcTemplate;

    public DefaultSkillRoleAdmin(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RoleSkillItem> getRoleSkills(String roleCode) {
        return jdbcTemplate.query(
                "SELECT market_skill_id, default_loaded FROM role_skill WHERE role_code = ? ORDER BY sort_order, market_skill_id",
                (rs, n) -> new RoleSkillItem(rs.getLong(1), rs.getBoolean(2)),
                roleCode);
    }

    @Override
    public List<MarketSkill> listRoleSkills(String roleCode) {
        return jdbcTemplate.query(
                "SELECT m.* FROM market_skill m JOIN role_skill r ON r.market_skill_id = m.id " +
                        "WHERE r.role_code = ? ORDER BY r.sort_order, m.id",
                (rs, n) -> new MarketSkill(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("content"),
                        rs.getString("author"),
                        rs.getString("status"),
                        rs.getTimestamp("submitted_at").toLocalDateTime(),
                        rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                        rs.getString("reviewed_by"),
                        rs.getString("review_comment")),
                roleCode);
    }

    @Override
    public void setRoleSkills(String roleCode, List<RoleSkillItem> items) {
        jdbcTemplate.update("DELETE FROM role_skill WHERE role_code = ?", roleCode);
        if (items != null && !items.isEmpty()) {
            int sort = 0;
            for (RoleSkillItem it : items) {
                if (it == null || it.marketSkillId() == null) continue;
                boolean def = it.defaultLoaded() == null || it.defaultLoaded();
                jdbcTemplate.update(
                        "INSERT INTO role_skill (role_code, market_skill_id, sort_order, default_loaded) VALUES (?, ?, ?, ?)",
                        roleCode, it.marketSkillId(), sort++, def);
            }
        }
    }
}
