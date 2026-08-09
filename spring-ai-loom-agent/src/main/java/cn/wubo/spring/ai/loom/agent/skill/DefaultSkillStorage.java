package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import cn.wubo.spring.ai.loom.agent.model.UserSkill;
import cn.wubo.spring.ai.loom.agent.model.UserSkillPatchRequest;
import cn.wubo.spring.ai.loom.agent.user.IUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 user_skill 表 + 角色授权自动同步。
 * 旧 admin 特权视图（market_skill APPROVED union）已移除—— admin 也只看到自己 user_skill，
 * 与普通用户行为完全一致。skill 数据一律从 user_skill 出，没有"市场 union"虚拟视图。
 * 旧 yml 嵌入的 skill 完全废弃（demo 数据改 seed 到默认 admin 的 user_skill，详见 __init_app_data.sql）。
 */
@Component
public class DefaultSkillStorage implements ISkillStorage {

 private static final Logger log = LoggerFactory.getLogger(DefaultSkillStorage.class);

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
 // 2) 取 user_skill —— admin 也只看到自己 user_skill，与普通用户行为一致
 return new ArrayList<>(queryUserSkills(username));
 }

 @Override
 public SkillRecord get(String name, String username) {
 sync(username);
 List<SkillRecord> userSkills = queryUserSkills(username);
 for (SkillRecord s : userSkills) {
 if (s.name().equals(name)) return s;
 }
 throw new LoomAgentRuntimeException("Skill 不存在或无权限: " + name);
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
 // 已存在就 update（同 name），否则 insert；locked 的不让改；MARKET_PULLED 内容锁定
 UserSkill existing = findUserSkill(username, skill.name());
 if (existing != null) {
 if (existing.locked()) {
 throw new LoomAgentRuntimeException("该 Skill 已被角色授权锁定，不能修改");
 }
 // MARKET_PULLED 内容锁定——只能通过 duplicate() 复制为新 USER_CREATED skill 才能改
 if ("MARKET_PULLED".equals(existing.source())) {
 throw new LoomAgentRuntimeException(403,
 "该技能从市场拉取，名称和内容不可直接修改。请先复制为我的技能后再修改。");
 }
 int updated = jdbcTemplate.update(
 "UPDATE user_skill SET description = ?, content = ?, default_loaded = ?, " +
 "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
 skill.description(), skill.content(), skill.load(), existing.id());

 // +：作者 USER_CREATED 且关联了 market_skill → 反向同步 + 推送给所有 MARKET_PULLED 拉取者
 if (existing.marketSkillId() != null) {
 // 1) 反向同步到 market_skill（仅 APPROVED/PENDING 才允许直接改；REJECTED 不动）
 int marketUpdated = jdbcTemplate.update(
 "UPDATE market_skill SET description = ?, content = ? " +
 "WHERE id = ? AND status IN ('APPROVED', 'PENDING') AND author = ?",
 skill.description(), skill.content(), existing.marketSkillId(), username);
 if (marketUpdated > 0) {
 // 2) 推送给所有 MARKET_PULLED 拉取者
 int pulled = jdbcTemplate.update(
 "UPDATE user_skill SET description = ?, content = ?, updated_at = CURRENT_TIMESTAMP " +
 "WHERE market_skill_id = ? AND source = 'MARKET_PULLED'",
 skill.description(), skill.content(), existing.marketSkillId());
 log.info("作者 save 推送: user={}, skill={}, market_skill_id={}, 推送 MARKET_PULLED 行数={}",
 username, skill.name(), existing.marketSkillId(), pulled);
 }
 }
 return updated;
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
 // USER_CREATED 且已共享到市场 → 必须先撤回（下架）才能删
 // 否则 market_skill 残留，author.user_skill.market_skill_id 引用断裂
 if ("USER_CREATED".equals(existing.source()) && existing.marketSkillId() != null) {
 throw new LoomAgentRuntimeException(403,
 "该技能已共享到市场，请先撤回共享（下架）后再删除。撤回入口：「我的发布」Tab。");
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
 // MARKET_PULLED 字段锁定更严——只允许 default_loaded，禁止改 desc
 // （name / content 走 save() 已统一拦截；patch 仅用于 toggle）
 if ("MARKET_PULLED".equals(existing.source())) {
 if (req.description() != null && !req.description().equals(existing.description())) {
 throw new LoomAgentRuntimeException(403,
 "该技能从市场获取，描述不可修改。如需自定义描述，请先复制为我的技能后再修改。");
 }
 // desc 不变；仅 defaultLoaded 可能改
 boolean def = req.defaultLoaded() == null ? existing.defaultLoaded() : req.defaultLoaded();
 return jdbcTemplate.update(
 "UPDATE user_skill SET default_loaded = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
 def, existing.id());
 }
 // USER_CREATED：desc + default_loaded 都允许
 String desc = req.description() == null ? existing.description() : req.description();
 boolean def = req.defaultLoaded() == null ? existing.defaultLoaded() : req.defaultLoaded();
 return jdbcTemplate.update(
 "UPDATE user_skill SET description = ?, default_loaded = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
 desc, def, existing.id());
 }

 @Override
 public String duplicate(String sourceName, String newName, String username) {
 UserSkill src = findUserSkill(username, sourceName);
 if (src == null) {
 throw new LoomAgentRuntimeException("Skill 不存在: " + sourceName);
 }
 if (src.locked()) {
 // ROLE_GRANTED 角色授权不允许复制
 throw new LoomAgentRuntimeException(403,
 "角色授权的 Skill 不允许复制（请直接使用，无需修改）");
 }
 // newName 可空：空时用「<sourceName>_副本」
 String base = (newName == null || newName.isBlank()) ? (sourceName + "_副本") : newName.trim();
 if (base.length() > 128) {
 throw new LoomAgentRuntimeException(400, "name 长度超过 128");
 }
 // 重名处理：依次尝试 base, base_2, base_3 ...
 String candidate = base;
 int seq = 2;
 while (findUserSkill(username, candidate) != null) {
 candidate = base + "_" + seq;
 seq++;
 if (seq > 999) {
 throw new LoomAgentRuntimeException(500,
 "复制失败：name 序号超过 999（" + base + "）");
 }
 }
 // INSERT 新 USER_CREATED 行：default_loaded 继承源，locked=FALSE
 jdbcTemplate.update(
 "INSERT INTO user_skill (username, name, description, content, source, default_loaded, locked) " +
 "VALUES (?, ?, ?, ?, 'USER_CREATED', ?, FALSE)",
 username, candidate, src.description(),
 SkillContentResolver.resolve(src.content(), resourceLoader),
 src.defaultLoaded());
 return candidate;
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
 "content = ?, description = ?, " +
 "default_loaded = ?, locked = TRUE, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
 mid, content, desc, def, existing.id());
 } else {
 jdbcTemplate.update(
 "INSERT INTO user_skill (username, name, description, content, source, market_skill_id, " +
 "default_loaded, locked) " +
 "VALUES (?, ?, ?, ?, 'ROLE_GRANTED', ?, ?, TRUE)",
 username, name, desc, content, mid, def);
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

 private UserSkill mapUserSkill(java.sql.ResultSet rs) throws java.sql.SQLException {
 return new UserSkill(
 rs.getLong("id"),
 rs.getString("username"),
 rs.getString("name"),
 rs.getString("description"),
 rs.getString("content"),
 rs.getString("source"),
 (Long) rs.getObject("market_skill_id"),
 rs.getBoolean("default_loaded"),
 rs.getBoolean("locked"),
 rs.getTimestamp("created_at").toLocalDateTime(),
 rs.getTimestamp("updated_at").toLocalDateTime());
 }
}
