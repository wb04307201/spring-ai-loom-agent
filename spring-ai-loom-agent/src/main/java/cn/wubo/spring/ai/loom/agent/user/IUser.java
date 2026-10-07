package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.UserInfo;
import cn.wubo.spring.ai.loom.agent.model.UserRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserResponseRecord;

import java.util.List;

public interface IUser {

    /**
     * 校验用户名 + 密码，成功返回带 token + nickname 的响应。
     * 失败抛 LoomAgentRuntimeException。
     */
    UserResponseRecord login(UserRequestRecord userRequestRecord);

    /**
     * 兼容旧接口：按 authentication token 查 username（保留供向后兼容）
     */
    String getUsernameByAuthentication(String authentication);

    /**
     * Generate a session token for the given username and store it in cache
     */
    String createToken(String username);

    /**
     * Validate a session token against cache
     */
    boolean validateToken(String token);

    /**
     * Invalidate (remove) a session token from cache
     */
    void invalidateToken(String token);

    /**
     * Get username from a valid token in cache
     */
    String getUsernameByToken(String token);

    /**
     * Get display nickname for a username (returns username if not found)
     */
    String getNicknameByUsername(String username);

    /**
     * Check whether the username has role ADMIN
     */
    boolean isAdmin(String username);

    /**
     * Change password for the given user (validates old password first)
     */
    void changePassword(String username, String oldPassword, String newPassword);

    /**
     * admin 重置他人密码（**不校验旧密码** —— admin 无从得知）。
     *
     * <p>与 {@link #changePassword} 的分工：后者是"本人改密"（校验旧密码），
     * 本方法是"管理员重置"（信任调用方已通过 admin 路由鉴权）。
     * 忘密码时若没有本方法，唯一办法是删号重建 —— 会丢会话/技能/角色。
     *
     * <p><b>权限边界</b>：目标用户为 ADMIN 时**一律拒绝**（即使系统有多个 admin）。
     * 理由：重置后可登录成对方账号，等价于静默接管；而删除是可察觉的破坏。
     * 故比 {@link #deleteUser} 的"最后一个管理员保护"更严格 —— 删除只保护最后一个，
     * 重置一个都不许。
     *
     * @param username    目标用户名
     * @param newPassword 新密码明文（至少 6 位），存储时 BCrypt 加盐
     * @throws cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException
     *         用户不存在 / 目标是管理员 / 密码不合规
     */
    void resetPassword(String username, String newPassword);

    /**
     * List all users (admin only — guard at route level)
     */
    List<UserInfo> listAllUsers();

    /**
     * Create a new user (admin only — guard at route level). Password is stored as BCrypt hash.
     */
    void createUser(String username, String nickname, String password, String type);

    /**
     * Delete a user. Throws if the target is the last remaining ADMIN.
     */
    void deleteUser(String username);

}
