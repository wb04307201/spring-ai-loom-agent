package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

}
