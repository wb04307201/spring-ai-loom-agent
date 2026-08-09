package cn.wubo.spring.ai.loom.agent.knowledge;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.KnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.model.MarketKnowledgeRecord;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class DefaultKnowledgeMarketService implements IKnowledgeMarketService {

 private final JdbcTemplate jdbcTemplate;
 private final IKnowledge knowledge;
 private final IUser user;

 public DefaultKnowledgeMarketService(JdbcTemplate jdbcTemplate, IKnowledge knowledge, IUser user) {
 this.jdbcTemplate = jdbcTemplate;
 this.knowledge = knowledge;
 this.user = user;
 }

 private MarketKnowledgeRecord mapMarketKnowledgeRecord(ResultSet rs, int rowNum) throws SQLException {
 Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
 Timestamp submittedAt = rs.getTimestamp("submitted_at");
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

 /* ===== 市场浏览 ===== */

 @Override
 public List<MarketKnowledgeRecord> listApproved(int page, int size) {
 if (page < 1) page = 1;
 if (size < 1) size = 20;
 int offset = (page - 1) * size;
 return jdbcTemplate.query(
 "SELECT * FROM loom_market_knowledge WHERE status = 'APPROVED' ORDER BY reviewed_at DESC, submitted_at DESC LIMIT ? OFFSET ?",
 this::mapMarketKnowledgeRecord, size, offset);
 }

 @Override
 public MarketKnowledgeRecord getById(String marketKnowledgeId) {
 try {
 return jdbcTemplate.queryForObject(
 "SELECT * FROM loom_market_knowledge WHERE id = ?",
 this::mapMarketKnowledgeRecord, marketKnowledgeId);
 } catch (EmptyResultDataAccessException e) {
 throw new LoomAgentRuntimeException(404, "市场知识库不存在: id=" + marketKnowledgeId);
 }
 }

 @Override
 public List<MarketKnowledgeRecord> listAllForAdmin() {
 // 无审批流，所有条目都是 APPROVED；按上架时间倒序
 return jdbcTemplate.query(
 "SELECT * FROM loom_market_knowledge ORDER BY reviewed_at DESC, submitted_at DESC",
 this::mapMarketKnowledgeRecord);
 }

 /* ===== 用户提交（直接 APPROVED，UPSERT 同一 username+name） ===== */

 @Override
 @Transactional
 public MarketKnowledgeRecord submit(String knowledgeId) {
 String username = UserContextHolder.getCurrentUser();
 if (username == null || username.isBlank()) {
 throw new LoomAgentRuntimeException(401, "未认证用户");
 }

 // 查询知识库并校验所有权
 List<KnowledgeRecord> userKbs = knowledge.list(username);
 KnowledgeRecord kb = userKbs.stream()
 .filter(k -> k.id().equals(knowledgeId))
 .findFirst()
 .orElseThrow(() -> new LoomAgentRuntimeException(404, "知识库不存在或不属于当前用户: " + knowledgeId));

 // UPSERT（同一 username+name 不限 status，只保留一行）
 String existingId = null;
 try {
 existingId = jdbcTemplate.queryForObject(
 "SELECT id FROM loom_market_knowledge WHERE username = ? AND name = ? LIMIT 1",
 String.class, username, kb.name());
 } catch (EmptyResultDataAccessException ignored) {}

 String marketId;
 if (existingId != null) {
 // 已存在 → UPDATE description + status='APPROVED' + 重置 reviewed_at
 jdbcTemplate.update(
 "UPDATE loom_market_knowledge SET description = ?, status = 'APPROVED', " +
 "reviewed_at = CURRENT_TIMESTAMP, reviewed_by = ?, review_comment = NULL WHERE id = ?",
 kb.description(), username, existingId);
 marketId = existingId;
 } else {
 // 不存在 → INSERT 全新行（直接 APPROVED）
 marketId = UUID.randomUUID().toString();
 jdbcTemplate.update(
 "INSERT INTO loom_market_knowledge (id, username, name, description, status, reviewed_at, reviewed_by) " +
 "VALUES (?, ?, ?, ?, 'APPROVED', CURRENT_TIMESTAMP, ?)",
 marketId, username, kb.name(), kb.description(), username);
 }
 return getById(marketId);
 }

 @Override
 public List<MarketKnowledgeRecord> listMySubmitted(String username) {
 return jdbcTemplate.query(
 "SELECT * FROM loom_market_knowledge WHERE username = ? ORDER BY reviewed_at DESC, submitted_at DESC",
 this::mapMarketKnowledgeRecord, username);
 }

 /* ===== 用户撤回 / admin 删除（统一端点 DELETE，权限内部判断） ===== */

 @Override
 @Transactional
 public void withdraw(String marketKnowledgeId) {
 String username = UserContextHolder.getCurrentUser();
 boolean isAdmin = user.isAdmin(username);
 MarketKnowledgeRecord existing = getById(marketKnowledgeId);
 if (!isAdmin && !existing.username().equals(username)) {
 throw new LoomAgentRuntimeException(403, "只能撤回/删除自己的提交");
 }
 // 清除引用（admin DELETE 也级联清理 user_knowledge + role_knowledge）
 jdbcTemplate.update("DELETE FROM loom_user_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
 jdbcTemplate.update("DELETE FROM loom_role_knowledge WHERE market_knowledge_id = ?", marketKnowledgeId);
 int rows;
 if (isAdmin) {
 rows = jdbcTemplate.update(
 "DELETE FROM loom_market_knowledge WHERE id = ?",
 marketKnowledgeId);
 } else {
 rows = jdbcTemplate.update(
 "DELETE FROM loom_market_knowledge WHERE id = ? AND username = ?",
 marketKnowledgeId, username);
 }
 if (rows == 0) {
 throw new LoomAgentRuntimeException(404, "市场知识库不存在: " + marketKnowledgeId);
 }
 }

 /* ===== 用户拉取（不再校验 status='APPROVED'，提交即上架） ===== */

 @Override
 @Transactional
 public void pull(String username, String marketKnowledgeId) {
 MarketKnowledgeRecord mk = getById(marketKnowledgeId);
 // 去掉 status='APPROVED' 校验（永远 APPROVED）

 // 检查是否已存在
 Integer existingCount = jdbcTemplate.queryForObject(
 "SELECT COUNT(*) FROM loom_user_knowledge WHERE username = ? AND market_knowledge_id = ?",
 Integer.class, username, marketKnowledgeId);
 if (existingCount != null && existingCount > 0) {
 throw new LoomAgentRuntimeException(409, "已订阅该知识库");
 }

 // 检查是否有 ROLE_GRANTED 锁定的同名订阅
 List<java.util.Map<String, Object>> lockedRows = jdbcTemplate.queryForList(
 "SELECT uk.* FROM loom_user_knowledge uk " +
 "JOIN loom_market_knowledge mk ON uk.market_knowledge_id = mk.id " +
 "WHERE uk.username = ? AND mk.name = ? AND uk.locked = TRUE",
 username, mk.name());
 if (!lockedRows.isEmpty()) {
 throw new LoomAgentRuntimeException(409, "同名知识库已被角色授权锁定，不能从市场覆盖");
 }

 jdbcTemplate.update(
 "INSERT INTO loom_user_knowledge (username, market_knowledge_id, source, locked) VALUES (?, ?, 'MARKET_PULLED', FALSE)",
 username, marketKnowledgeId);
 }

 @Override
 public List<MarketKnowledgeRecord> listMyPulled(String username) {
 return jdbcTemplate.query(
 "SELECT mk.* FROM loom_market_knowledge mk " +
 "JOIN loom_user_knowledge uk ON mk.id = uk.market_knowledge_id " +
 "WHERE uk.username = ? AND uk.source = 'MARKET_PULLED' " +
 "ORDER BY mk.reviewed_at DESC, mk.submitted_at DESC",
 this::mapMarketKnowledgeRecord, username);
 }
}