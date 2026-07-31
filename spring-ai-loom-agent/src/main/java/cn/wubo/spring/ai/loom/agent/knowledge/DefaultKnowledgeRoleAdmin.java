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
                "SELECT market_knowledge_id, default_enabled FROM loom_role_knowledge WHERE role_code = ? ORDER BY sort_order, market_knowledge_id",
                (rs, rowNum) -> new RoleKnowledgeItem(rs.getString(1), rs.getBoolean(2)),
                roleCode);
    }

    @Override
    public List<MarketKnowledgeRecord> listRoleKnowledges(String roleCode) {
        return jdbcTemplate.query(
                "SELECT mk.* FROM loom_market_knowledge mk " +
                        "JOIN loom_role_knowledge rk ON rk.market_knowledge_id = mk.id " +
                        "WHERE rk.role_code = ? ORDER BY rk.sort_order, mk.id",
                this::mapMarketKnowledgeRecord,
                roleCode);
    }

    @Override
    public void setRoleKnowledges(String roleCode, List<RoleKnowledgeItem> items) {
        jdbcTemplate.update("DELETE FROM loom_role_knowledge WHERE role_code = ?", roleCode);
        if (items != null && !items.isEmpty()) {
            int sort = 0;
            for (RoleKnowledgeItem it : items) {
                if (it == null || it.marketKnowledgeId() == null || it.marketKnowledgeId().isBlank()) continue;
                boolean def = it.defaultEnabled() == null ? false : it.defaultEnabled();
                jdbcTemplate.update(
                        "INSERT INTO loom_role_knowledge (role_code, market_knowledge_id, sort_order, default_enabled) VALUES (?, ?, ?, ?)",
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
                            "FROM loom_role_knowledge rk JOIN loom_market_knowledge mk ON rk.market_knowledge_id = mk.id " +
                            "WHERE rk.role_code = ?", role);

            for (Map<String, Object> rk : roleKbs) {
                String kbId = (String) rk.get("mid");
                String name = (String) rk.get("name");
                boolean enabled = Boolean.TRUE.equals(rk.get("def"));

                // Check if user already has this exact subscription
                Integer exists = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM loom_user_knowledge WHERE username = ? AND market_knowledge_id = ?",
                        Integer.class, username, kbId);
                if (exists != null && exists > 0) {
                    // Already subscribed, ensure locked status is correct
                    jdbcTemplate.update(
                            "UPDATE loom_user_knowledge SET source = 'ROLE_GRANTED', locked = TRUE WHERE username = ? AND market_knowledge_id = ?",
                            username, kbId);
                    continue;
                }

                // Check for name conflict with locked entry
                List<Map<String, Object>> lockedByName = jdbcTemplate.queryForList(
                        "SELECT uk.market_knowledge_id FROM loom_user_knowledge uk " +
                                "JOIN loom_market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                                "WHERE uk.username = ? AND mk.name = ? AND uk.locked = TRUE",
                        username, name);
                if (!lockedByName.isEmpty()) {
                    // Name conflict with locked entry, skip
                    continue;
                }

                // Check for name conflict with non-locked entry
                // FIX #5: Use two-step approach (SELECT then UPDATE) instead of self-referential subquery
                List<Map<String, Object>> existingByName = jdbcTemplate.queryForList(
                        "SELECT uk.market_knowledge_id AS existing_id FROM loom_user_knowledge uk " +
                                "JOIN loom_market_knowledge mk ON uk.market_knowledge_id = mk.id " +
                                "WHERE uk.username = ? AND mk.name = ? AND uk.locked = FALSE",
                        username, name);
                if (!existingByName.isEmpty()) {
                    // Update existing non-locked entry to ROLE_GRANTED
                    String existingId = (String) existingByName.get(0).get("existing_id");
                    jdbcTemplate.update(
                            "UPDATE loom_user_knowledge SET source = 'ROLE_GRANTED', market_knowledge_id = ?, locked = TRUE WHERE username = ? AND market_knowledge_id = ?",
                            kbId, username, existingId);
                } else {
                    // Insert new
                    jdbcTemplate.update(
                            "INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked) VALUES (?, ?, 'ROLE_GRANTED', TRUE)",
                            username, kbId);
                }
            }
        }
    }

    private MarketKnowledgeRecord mapMarketKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
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
    }
}
