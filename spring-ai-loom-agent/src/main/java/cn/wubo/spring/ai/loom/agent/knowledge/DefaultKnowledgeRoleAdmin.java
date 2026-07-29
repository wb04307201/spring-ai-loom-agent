package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.RoleKnowledgeItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Component
public class DefaultKnowledgeRoleAdmin implements IKnowledgeRoleAdmin {

    private final JdbcTemplate jdbcTemplate;
    private final IKnowledgeMarketService marketService;

    public DefaultKnowledgeRoleAdmin(JdbcTemplate jdbcTemplate, IKnowledgeMarketService marketService) {
        this.jdbcTemplate = jdbcTemplate;
        this.marketService = marketService;
    }

    @Override
    public List<RoleKnowledgeItem> getRoleKnowledges(String roleCode) {
        return jdbcTemplate.query(
                "SELECT market_knowledge_id, default_enabled FROM role_knowledge WHERE role_code = ? ORDER BY sort_order, market_knowledge_id",
                (rs, rowNum) -> new RoleKnowledgeItem(rs.getString(1), rs.getBoolean(2)),
                roleCode);
    }

    @Override
    public List<MarketKnowledgeRecord> listRoleKnowledges(String roleCode) {
        return jdbcTemplate.query(
                "SELECT mk.* FROM market_knowledge mk " +
                        "JOIN role_knowledge rk ON rk.market_knowledge_id = mk.id " +
                        "WHERE rk.role_code = ? ORDER BY rk.sort_order, mk.id",
                (rs, rowNum) -> {
                    java.sql.Timestamp submittedAt = rs.getTimestamp("submitted_at");
                    java.sql.Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
                    return new MarketKnowledgeRecord(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("status"),
                            submittedAt == null ? null : submittedAt.toLocalDateTime(),
                            reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
                            rs.getString("reviewed_by"),
                            rs.getString("review_comment"));
                },
                roleCode);
    }

    @Override
    public void setRoleKnowledges(String roleCode, List<RoleKnowledgeItem> items) {
        jdbcTemplate.update("DELETE FROM role_knowledge WHERE role_code = ?", roleCode);
        if (items != null && !items.isEmpty()) {
            int sort = 0;
            for (RoleKnowledgeItem it : items) {
                if (it == null || it.marketKnowledgeId() == null || it.marketKnowledgeId().isBlank()) continue;
                boolean def = it.defaultEnabled() == null ? false : it.defaultEnabled();
                jdbcTemplate.update(
                        "INSERT INTO role_knowledge (role_code, market_knowledge_id, sort_order, default_enabled) VALUES (?, ?, ?, ?)",
                        roleCode, it.marketKnowledgeId(), sort++, def);
            }
        }
    }

    @Override
    public void syncUserKnowledge(String username) {
        // Get user roles
        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role_code FROM user_role WHERE username = ?", String.class, username);

        if (roles == null || roles.isEmpty()) {
            return;
        }

        // For each role, get its associated market knowledge bases
        for (String role : roles) {
            List<Map<String, Object>> roleKbs = jdbcTemplate.queryForList(
                    "SELECT rk.market_knowledge_id AS mid, rk.default_enabled AS def, mk.name " +
                            "FROM role_knowledge rk JOIN market_knowledge mk ON rk.market_knowledge_id = mk.id " +
                            "WHERE rk.role_code = ?", role);

            for (Map<String, Object> rk : roleKbs) {
                String kbId = (String) rk.get("mid");
                String name = (String) rk.get("name");
                boolean enabled = Boolean.TRUE.equals(rk.get("def"));

                // Check if user already has this exact subscription
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                        Integer.class, username, kbId);
                if (exists != null && exists > 0) {
                    // Already subscribed, ensure locked status is correct
                    jdbcTemplate.update(
                            "UPDATE user_knowledge SET source = 'ROLE_GRANTED', locked = TRUE WHERE username = ? AND market_knowledge_id = ?",
                            username, kbId);
                    continue;
                }

                // Check for name conflict with locked entry
                List<Map<String, Object>> lockedByName = jdbcTemplate.queryForList(
                        "SELECT uk.* FROM user_knowledge uk " +
                                "JOIN market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                                "WHERE uk.username = ? AND mk.name = ? AND uk.locked = TRUE",
                        username, name);
                if (!lockedByName.isEmpty()) {
                    // Name conflict with locked entry, skip
                    continue;
                }

                // Check for name conflict with non-locked entry
                List<Map<String, Object>> existingByName = jdbcTemplate.queryForList(
                        "SELECT uk.* FROM user_knowledge uk " +
                                "JOIN market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                                "WHERE uk.username = ? AND mk.name = ? AND uk.locked = FALSE",
                        username, name);
                if (!existingByName.isEmpty()) {
                    // Update existing non-locked entry to ROLE_GRANTED
                    jdbcTemplate.update(
                            "UPDATE user_knowledge SET source = 'ROLE_GRANTED', market_knowledge_id = ?, locked = TRUE WHERE username = ? AND market_knowledge_id = (SELECT market_knowledge_id FROM user_knowledge WHERE username = ? AND locked = FALSE AND market_knowledge_id IN (SELECT id FROM market_knowledge WHERE name = ?))",
                            kbId, username, username, name);
                } else {
                    // Insert new
                    jdbcTemplate.update(
                            "INSERT INTO user_knowledge (username, market_knowledge_id, source, locked) VALUES (?, ?, 'ROLE_GRANTED', TRUE)",
                            username, kbId);
                }
            }
        }
    }
}
