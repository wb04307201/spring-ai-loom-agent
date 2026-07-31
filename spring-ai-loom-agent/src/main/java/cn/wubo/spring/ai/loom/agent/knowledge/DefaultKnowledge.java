package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DefaultKnowledge implements IKnowledge {

    private final JdbcTemplate jdbcTemplate;

    public DefaultKnowledge(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private KnowledgeRecord mapKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description")
        );
    }

    @Override
    public List<KnowledgeRecord> list() {
        String username = UserContextHolder.getCurrentUser();
        return jdbcTemplate.query(
                "SELECT * FROM knowledge where username = ?",
                this::mapKnowledgeRecord,
                username
        );
    }

    @Override
    public List<KnowledgeRecord> list(String username) {
        return jdbcTemplate.query(
                "SELECT * FROM knowledge where username = ?",
                this::mapKnowledgeRecord,
                username
        );
    }

    @Override
    public KnowledgeRecord insert(String name, String description) {
        String username = UserContextHolder.getCurrentUser();
        // Same-user duplicate is rejected with a clean 4xx instead of 500.
        Integer dupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge WHERE username = ? AND name = ?",
                Integer.class,
                username,
                name
        );
        if (dupCount != null && dupCount > 0) {
            throw new LoomAgentRuntimeException(409, "知识库名称重复：" + name);
        }
        KnowledgeRecord knowledgeRecord = new KnowledgeRecord(
                UUID.randomUUID().toString(),
                username,
                name,
                description
        );
        try {
            jdbcTemplate.update(
                    "INSERT INTO knowledge (id, username, name, description) VALUES (?, ?, ?, ?)",
                    knowledgeRecord.id(),
                    knowledgeRecord.username(),
                    knowledgeRecord.name(),
                    knowledgeRecord.description()
            );
        } catch (DuplicateKeyException e) {
            // Race condition: another request created the same name between our
            // check above and the INSERT. Translate to a clean 4xx.
            throw new LoomAgentRuntimeException(409, "知识库名称重复：" + name);
        }
        return knowledgeRecord;
    }

    @Override
    public KnowledgeRecord update(String id, String name, String description) {
        String username = UserContextHolder.getCurrentUser();
        // Check for duplicate name among other knowledge bases (exclude self)
        Integer dupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge WHERE username = ? AND name = ? AND id != ?",
                Integer.class,
                username,
                name,
                id
        );
        if (dupCount != null && dupCount > 0) {
            throw new LoomAgentRuntimeException(409, "知识库名称重复：" + name);
        }
        try {
            int rows = jdbcTemplate.update(
                    "UPDATE knowledge SET name = ?, description = ? WHERE id = ? AND username = ?",
                    name,
                    description,
                    id,
                    username
            );
            if (rows == 0) {
                throw new LoomAgentRuntimeException(404, "知识库不存在：" + id);
            }
            return new KnowledgeRecord(id, username, name, description);
        } catch (DuplicateKeyException e) {
            throw new LoomAgentRuntimeException(409, "知识库名称重复：" + name);
        }
    }

    @Override
    public int delete(String id) {
        // BUG-12: cross-user KB delete. Scope by the current authenticated
        // user so a USER cannot DELETE another user's knowledge by guessing
        // the row id. Returns 0 if the row doesn't exist or doesn't belong
        // to the caller.
        String username = UserContextHolder.getCurrentUser();
        return jdbcTemplate.update(
                "DELETE FROM knowledge WHERE id = ? AND username = ?",
                id, username
        );
    }

    @Override
    public List<KnowledgeRecord> listAccessible(String username) {
        // 1) Own knowledge bases
        List<KnowledgeRecord> result = new ArrayList<>(list(username));
        Set<String> seenIds = new HashSet<>();
        for (KnowledgeRecord kr : result) {
            seenIds.add(kr.id());
        }

        // 2) Market-pulled knowledge bases (from loom_user_knowledge JOIN loom_market_knowledge)
        List<KnowledgeRecord> pulled = jdbcTemplate.query(
                "SELECT mk.id, mk.username, mk.name, mk.description FROM loom_market_knowledge mk " +
                        "JOIN loom_user_knowledge uk ON mk.id = uk.market_knowledge_id " +
                        "WHERE uk.username = ? AND uk.source = 'MARKET_PULLED'",
                this::mapMarketToKnowledgeRecord, username);
        for (KnowledgeRecord kr : pulled) {
            if (!seenIds.contains(kr.id())) {
                result.add(kr);
                seenIds.add(kr.id());
            }
        }

        // 3) Role-granted knowledge bases
        List<KnowledgeRecord> roleGranted = jdbcTemplate.query(
                "SELECT mk.id, mk.username, mk.name, mk.description FROM loom_market_knowledge mk " +
                        "JOIN loom_user_knowledge uk ON mk.id = uk.market_knowledge_id " +
                        "WHERE uk.username = ? AND uk.source = 'ROLE_GRANTED'",
                this::mapMarketToKnowledgeRecord, username);
        for (KnowledgeRecord kr : roleGranted) {
            if (!seenIds.contains(kr.id())) {
                result.add(kr);
                seenIds.add(kr.id());
            }
        }

        return result;
    }

    @Override
    public boolean canEdit(String knowledgeId) {
        String username = UserContextHolder.getCurrentUser();
        if (username == null || username.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM knowledge WHERE id = ? AND username = ?",
                Integer.class, knowledgeId, username);
        return count != null && count > 0;
    }

    private KnowledgeRecord mapMarketToKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
        return new KnowledgeRecord(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("name"),
                rs.getString("description")
        );
    }
}
