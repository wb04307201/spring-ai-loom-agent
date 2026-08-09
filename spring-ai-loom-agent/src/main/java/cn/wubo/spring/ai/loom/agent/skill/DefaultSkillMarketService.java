package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.MarketSkill;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillSubmitRequest;
import cn.wubo.spring.ai.loom.agent.model.MarketSkillUpsertRequest;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class DefaultSkillMarketService implements ISkillMarketService {

 private final JdbcTemplate jdbcTemplate;

 public DefaultSkillMarketService(JdbcTemplate jdbcTemplate) {
 this.jdbcTemplate = jdbcTemplate;
 }

 private MarketSkill mapMarketSkill(ResultSet rs, int rowNum) throws SQLException {
 Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
 return new MarketSkill(
 rs.getLong("id"),
 rs.getString("name"),
 rs.getString("description"),
 rs.getString("content"),
 rs.getString("author"),
 rs.getString("status"),
 rs.getTimestamp("submitted_at").toLocalDateTime(),
 reviewedAt == null ? null : reviewedAt.toLocalDateTime(),
 rs.getString("reviewed_by"),
 rs.getString("review_comment")
 );
 }

 /* ===== 市场浏览 ===== */

 @Override
 public List<MarketSkill> listApproved() {
 return jdbcTemplate.query(
 "SELECT * FROM market_skill WHERE status = 'APPROVED' ORDER BY author, name",
 this::mapMarketSkill);
 }

 @Override
 public MarketSkill get(Long id) {
 try {
 return jdbcTemplate.queryForObject(
 "SELECT * FROM market_skill WHERE id = ?",
 this::mapMarketSkill, id);
 } catch (EmptyResultDataAccessException e) {
 throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
 }
 }

 @Override
 public List<MarketSkill> listAllForAdmin() {
 // 移除 version 字段，排序按 author, name
 return jdbcTemplate.query(
 "SELECT * FROM market_skill ORDER BY author, name",
 this::mapMarketSkill);
 }

 /* ===== 用户提交（移除 version） ===== */

 @Override
 @Transactional
 public MarketSkill submit(String username, MarketSkillSubmitRequest req) {
 if (req.name() == null || req.name().isBlank()) {
 throw new LoomAgentRuntimeException("name 不能为空");
 }
 if (req.content() == null || req.content().isBlank()) {
 throw new LoomAgentRuntimeException("content 不能为空");
 }
 // UPSERT（同一作者+name 只保留一行）+ 直接 APPROVED
 Long existingId = null;
 try {
 existingId = jdbcTemplate.queryForObject(
 "SELECT id FROM market_skill WHERE author = ? AND name = ? LIMIT 1",
 Long.class, username, req.name());
 } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {}
 Long marketId;
 if (existingId != null) {
 // UPDATE 内容 + 标记 APPROVED
 jdbcTemplate.update(
 "UPDATE market_skill SET description = ?, content = ?, status = 'APPROVED', " +
 "reviewed_at = CURRENT_TIMESTAMP, reviewed_by = ?, review_comment = NULL WHERE id = ?",
 req.description(), req.content(), username, existingId);
 marketId = existingId;
 } else {
 jdbcTemplate.update(
 "INSERT INTO market_skill (name, description, content, author, status, reviewed_at, reviewed_by) " +
 "VALUES (?, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, ?)",
 req.name(), req.description(), req.content(), username, username);
 marketId = jdbcTemplate.queryForObject(
 "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ?",
 Long.class, username, req.name());
 }
 // 反写 author 自己的 user_skill.market_skill_id（用于 save() 反向同步 + 推送）
 jdbcTemplate.update(
 "UPDATE user_skill SET market_skill_id = ? WHERE username = ? AND name = ?",
 marketId, username, req.name());
 return get(marketId);
 }

 /* ===== admin 直接 CRUD（移除 version） ===== */

 @Override
 @Transactional
 public MarketSkill adminCreate(String adminUsername, MarketSkillUpsertRequest req) {
 if (req.name() == null || req.name().isBlank()) {
 throw new LoomAgentRuntimeException("name 不能为空");
 }
 if (req.content() == null || req.content().isBlank()) {
 throw new LoomAgentRuntimeException("content 不能为空");
 }
 String status = req.status() == null ? MarketSkill.STATUS_APPROVED : req.status();
 Integer dup = jdbcTemplate.queryForObject(
 "SELECT COUNT(*) FROM market_skill WHERE author = ? AND name = ?",
 Integer.class, adminUsername, req.name());
 if (dup != null && dup > 0) {
 throw new LoomAgentRuntimeException("已存在同名 Skill：author=" + adminUsername + " name=" + req.name());
 }
 jdbcTemplate.update(
 "INSERT INTO market_skill (name, description, content, author, status, " +
 "reviewed_at, reviewed_by) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
 req.name(), req.description(), req.content(),
 adminUsername, status, adminUsername);
 Long id = jdbcTemplate.queryForObject(
 "SELECT MAX(id) FROM market_skill WHERE author = ? AND name = ?",
 Long.class, adminUsername, req.name());
 return get(id);
 }

 @Override
 @Transactional
 public MarketSkill adminUpdate(String adminUsername, Long id, MarketSkillUpsertRequest req) {
 MarketSkill existing = get(id);
 jdbcTemplate.update(
 "UPDATE market_skill SET name = ?, description = ?, content = ?, " +
 "status = COALESCE(?, status) WHERE id = ?",
 req.name() == null ? existing.name() : req.name(),
 req.description() == null ? existing.description() : req.description(),
 req.content() == null ? existing.content() : req.content(),
 req.status(),
 id);
 return get(id);
 }

 @Override
 @Transactional
 public void adminDelete(String adminUsername, Long id) {
 // 先把 user_skill / role_skill 里所有引用清掉
 jdbcTemplate.update("DELETE FROM user_skill WHERE market_skill_id = ?", id);
 jdbcTemplate.update("DELETE FROM role_skill WHERE market_skill_id = ?", id);
 int n = jdbcTemplate.update("DELETE FROM market_skill WHERE id = ?", id);
 if (n == 0) throw new LoomAgentRuntimeException("Skill 不存在: id=" + id);
 }

 /* ===== 用户拉取 ===== */

 @Override
 @Transactional
 public UserSkill pull(String username, Long marketSkillId) {
 MarketSkill m = get(marketSkillId);
 // 去掉 status='APPROVED' 校验（提交即上架）
 // 检查 user_skill 是否已存在同 name
 List<UserSkill> existing = jdbcTemplate.query(
 "SELECT * FROM user_skill WHERE username = ? AND name = ?",
 (rs, n) -> new UserSkill(
 rs.getLong("id"), rs.getString("username"), rs.getString("name"),
 rs.getString("description"), rs.getString("content"), rs.getString("source"),
 (Long) rs.getObject("market_skill_id"),
 rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
 rs.getTimestamp("created_at").toLocalDateTime(),
 rs.getTimestamp("updated_at").toLocalDateTime()),
 username, m.name());
 if (!existing.isEmpty()) {
 UserSkill e = existing.get(0);
 // 按 source 区分（USER_CREATED 不能被 pull 覆盖，否则会"吃掉"用户自建的内容）
 if ("USER_CREATED".equals(e.source())) {
 throw new LoomAgentRuntimeException(403,
 "你已有同名自建 skill「" + m.name() + "」，不能从市场覆盖。如需使用市场版本，请先删除自建版本。");
 }
 // MARKET_PULLED：刷新 content（拉取最新市场快照）
 jdbcTemplate.update(
 "UPDATE user_skill SET description = ?, content = ?, source = 'MARKET_PULLED', " +
 "market_skill_id = ?, updated_at = CURRENT_TIMESTAMP " +
 "WHERE id = ?",
 m.description(), m.content(), m.id(), e.id());
 return getUserSkill(e.id());
 }
 jdbcTemplate.update(
 "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, " +
 "default_loaded, locked) VALUES (?, ?, ?, ?, 'MARKET_PULLED', ?, TRUE, FALSE)",
 username, m.name(), m.description(), m.content(), m.id());
 Long id = jdbcTemplate.queryForObject(
 "SELECT MAX(id) FROM user_skill WHERE username = ? AND name = ?",
 Long.class, username, m.name());
 return getUserSkill(id);
 }

 /* ===== 用户查看/撤回 ===== */

 @Override
 public List<MarketSkill> listMySubmitted(String username) {
 return jdbcTemplate.query(
 "SELECT * FROM market_skill WHERE author = ? ORDER BY submitted_at DESC",
 this::mapMarketSkill, username);
 }

 @Override
 @Transactional
 public boolean withdraw(String username, Long marketSkillId) {
 // 去掉 status='PENDING' 限制（任意状态可撤回）
 int rows = jdbcTemplate.update(
 "DELETE FROM market_skill WHERE id = ? AND author = ?",
 marketSkillId, username);
 if (rows > 0) {
 // 反清空 author 自己的 user_skill.market_skill_id（断绝反向同步链路）
 jdbcTemplate.update(
 "UPDATE user_skill SET market_skill_id = NULL WHERE username = ? AND market_skill_id = ?",
 username, marketSkillId);
 }
 return rows > 0;
 }

 private UserSkill getUserSkill(Long id) {
 return jdbcTemplate.queryForObject(
 "SELECT * FROM user_skill WHERE id = ?",
 (rs, n) -> new UserSkill(
 rs.getLong("id"), rs.getString("username"), rs.getString("name"),
 rs.getString("description"), rs.getString("content"), rs.getString("source"),
 (Long) rs.getObject("market_skill_id"),
 rs.getBoolean("default_loaded"), rs.getBoolean("locked"),
 rs.getTimestamp("created_at").toLocalDateTime(),
 rs.getTimestamp("updated_at").toLocalDateTime()),
 id);
 }

 private void validateName(String name) {
 if (name == null || name.isBlank()) {
 throw new LoomAgentRuntimeException("name 不能为空");
 }
 }
}
