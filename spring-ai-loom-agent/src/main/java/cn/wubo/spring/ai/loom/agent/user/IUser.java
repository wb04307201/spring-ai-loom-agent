package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.UserRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserResponseRecord;

public interface IUser {

    Boolean isAutoLogin();

    UserResponseRecord login(UserRequestRecord userRequestRecord);

    String getUsernameByAuthentication(String authentication);

    /** Generate a session token for the given username and store it in cache */
    String createToken(String username);

    /** Validate a session token against cache */
    boolean validateToken(String token);

    /** Invalidate (remove) a session token from cache */
    void invalidateToken(String token);

    /** Get username from a valid token in cache */
    String getUsernameByToken(String token);

}
