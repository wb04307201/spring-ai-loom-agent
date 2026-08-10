package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.UserInfo;
import cn.wubo.spring.ai.loom.agent.model.UserRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserResponseRecord;
import org.springframework.cache.Cache;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DefaultUser implements IUser {

    private static final String TYPE_ADMIN = "ADMIN";
    private static final String TYPE_USER = "USER";
    /**
     * Defensive input caps for createUser — see the comment in createUser.
     */
    private static final int MAX_USERNAME = 64;
    private static final int MAX_NICKNAME = 64;
    private static final int MAX_PASSWORD = 128;

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final Cache sessionCache;

    public DefaultUser(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder, Cache sessionCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.sessionCache = sessionCache;
    }

    // ---- session ----

    @Override
    public String createToken(String username) {
        String sessionToken = UUID.randomUUID().toString();
        sessionCache.put(sessionToken, username);
        return sessionToken;
    }

    @Override
    public boolean validateToken(String token) {
        return token != null && sessionCache.get(token) != null;
    }

    @Override
    public void invalidateToken(String token) {
        if (token != null) sessionCache.evict(token);
    }

    @Override
    public String getUsernameByToken(String token) {
        if (token == null) return null;
        Cache.ValueWrapper wrapper = sessionCache.get(token);
        return wrapper != null ? (String) wrapper.get() : null;
    }

    @Override
    public String getUsernameByAuthentication(String authentication) {
        if (authentication == null) {
            throw new LoomAgentRuntimeException("获取用户信息失败");
        }
        String username = getUsernameByToken(authentication);
        if (username == null) {
            throw new LoomAgentRuntimeException("获取用户信息失败");
        }
        return username;
    }

    // ---- login ----

    @Override
    public UserResponseRecord login(UserRequestRecord req) {
        if (req == null || req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().isBlank()) {
            throw new LoomAgentRuntimeException("用户名或密码不能为空");
        }
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT nickname, password FROM user_info WHERE username = ?",
                    req.username());
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException("用户名或密码错误");
        }
        String storedHash = (String) row.get("password");
        if (storedHash == null || !passwordEncoder.matches(req.password(), storedHash)) {
            throw new LoomAgentRuntimeException("用户名或密码错误");
        }
        String nickname = (String) row.get("nickname");
        String token = createToken(req.username());
        return new UserResponseRecord(token, nickname);
    }

    // ---- profile / role ----

    @Override
    public String getNicknameByUsername(String username) {
        if (username == null) return null;
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT nickname FROM user_info WHERE username = ?", String.class, username);
        } catch (EmptyResultDataAccessException e) {
            return username;
        }
    }

    @Override
    public boolean isAdmin(String username) {
        if (username == null) return false;
        try {
            String type = jdbcTemplate.queryForObject(
                    "SELECT type FROM user_info WHERE username = ?", String.class, username);
            return TYPE_ADMIN.equals(type);
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public void changePassword(String username, String oldPassword, String newPassword) {
        if (username == null || oldPassword == null || newPassword == null) {
            throw new LoomAgentRuntimeException("参数不能为空");
        }
        if (newPassword.length() < 6) {
            throw new LoomAgentRuntimeException("新密码至少 6 位");
        }
        String storedHash;
        try {
            storedHash = jdbcTemplate.queryForObject(
                    "SELECT password FROM user_info WHERE username = ?", String.class, username);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException("用户不存在");
        }
        if (storedHash == null || !passwordEncoder.matches(oldPassword, storedHash)) {
            throw new LoomAgentRuntimeException("旧密码错误");
        }
        String newHash = passwordEncoder.encode(newPassword);
        int updated = jdbcTemplate.update(
                "UPDATE user_info SET password = ? WHERE username = ?", newHash, username);
        if (updated == 0) {
            throw new LoomAgentRuntimeException("修改失败");
        }
    }

    // ---- admin ops ----

    @Override
    public List<UserInfo> listAllUsers() {
        return jdbcTemplate.query(
                "SELECT username, nickname, type FROM user_info ORDER BY id",
                (rs, rowNum) -> new UserInfo(rs.getString("username"),
                        rs.getString("nickname"),
                        rs.getString("type")));
    }

    @Override
    public void createUser(String username, String nickname, String password, String type) {
        if (username == null || username.isBlank()
                || nickname == null || nickname.isBlank()
                || password == null || password.length() < 6
                || type == null) {
            throw new LoomAgentRuntimeException("参数不合法（用户名/昵称必填，密码至少 6 位）");
        }
        // Defensive length caps so a hostile admin POST cannot push a 100KB
        // username or a 1MB password through BCrypt (which would burn CPU
        // proportional to plaintext size).
        if (username.length() > MAX_USERNAME || nickname.length() > MAX_NICKNAME
                || password.length() > MAX_PASSWORD) {
            throw new LoomAgentRuntimeException(
                    "参数过长：用户名 ≤ " + MAX_USERNAME + "，昵称 ≤ " + MAX_NICKNAME
                            + "，密码 ≤ " + MAX_PASSWORD);
        }
        if (!TYPE_ADMIN.equals(type) && !TYPE_USER.equals(type)) {
            throw new LoomAgentRuntimeException("type 必须是 ADMIN 或 USER");
        }
        // Username MUST NOT contain '-' because the schedule-task namespace format is
        // "loom-sched-{username}-{uuid36}-{name}" and the frontend's _shortName() parser
        // splits on the FIRST '-' to strip the username segment. A dashed username would
        // leave an ambiguous prefix and corrupt the short-name heuristic.
        // See app.js _shortName (around the schedule list rendering).
        if (username.contains("-")) {
            throw new LoomAgentRuntimeException("用户名不能包含 '-'，与调度任务命名空间冲突");
        }
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new LoomAgentRuntimeException("用户名已存在");
        }
        String hash = passwordEncoder.encode(password);
        jdbcTemplate.update(
                "INSERT INTO user_info (username, nickname, password, type) VALUES (?, ?, ?, ?)",
                username, nickname, hash, type);
    }

    @Override
    public void deleteUser(String username) {
        if (username == null) {
            throw new LoomAgentRuntimeException("参数不能为空");
        }
        // 检查目标用户是否存在
        String targetType;
        try {
            targetType = jdbcTemplate.queryForObject(
                    "SELECT type FROM user_info WHERE username = ?", String.class, username);
        } catch (EmptyResultDataAccessException e) {
            throw new LoomAgentRuntimeException("用户不存在");
        }
        // 最后一个管理员保护
        if (TYPE_ADMIN.equals(targetType)) {
            Integer adminCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM user_info WHERE type = 'ADMIN'", Integer.class);
            if (adminCount != null && adminCount <= 1) {
                throw new LoomAgentRuntimeException("系统至少保留一个管理员，无法删除最后一个管理员");
            }
        }
        int deleted = jdbcTemplate.update("DELETE FROM user_info WHERE username = ?", username);
        if (deleted == 0) {
            throw new LoomAgentRuntimeException("删除失败");
        }
    }
}
