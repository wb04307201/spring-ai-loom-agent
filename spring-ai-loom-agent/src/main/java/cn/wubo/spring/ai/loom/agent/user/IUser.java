package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.UserInfo;
import cn.wubo.spring.ai.loom.agent.model.UserRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserResponseRecord;

import java.util.List;

public interface IUser {

    /**
     * 检查 session cookie 是否有效（true 表示已登录）。
     * 用于前端在进入 index.html 时判断是否要跳转到 login.html。
     */
    Boolean isAutoLogin();

    /**
     * 校验用户名 + 密码，成功返回带 token + nickname 的响应。
     * 失败抛 LoomAgentRuntimeException。
     */
    UserResponseRecord login(UserRequestRecord userRequestRecord);

    /** 兼容旧接口：按 authentication token 查 username（保留供向后兼容） */
    String getUsernameByAuthentication(String authentication);

    /** Generate a session token for the given username and store it in cache */
    String createToken(String username);

    /** Validate a session token against cache */
    boolean validateToken(String token);

    /** Invalidate (remove) a session token from cache */
    void invalidateToken(String token);

    /** Get username from a valid token in cache */
    String getUsernameByToken(String token);

    /** Get display nickname for a username (returns username if not found) */
    String getNicknameByUsername(String username);

    /** Check whether the username has role ADMIN */
    boolean isAdmin(String username);

    /** Change password for the given user (validates old password first) */
    void changePassword(String username, String oldPassword, String newPassword);

    /** List all users (admin only — guard at route level) */
    List<UserInfo> listAllUsers();

    /** Create a new user (admin only — guard at route level). Password is stored as BCrypt hash. */
    void createUser(String username, String nickname, String password, String type);

    /** Delete a user. Throws if the target is the last remaining ADMIN. */
    void deleteUser(String username);

}
