package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.UserRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.UserResponseRecord;
import org.springframework.cache.Cache;

import java.util.UUID;

public class DefaultUser implements IUser {

    private final String defaultUsername;
    private final String defaultNickname;
    private final String token;
    private final Cache sessionCache;

    public DefaultUser(String defaultUsername, String defaultNickname, String token, Cache sessionCache) {
        this.defaultUsername = defaultUsername;
        this.defaultNickname = defaultNickname;
        this.token = token;
        this.sessionCache = sessionCache;
    }

    @Override
    public Boolean isAutoLogin() {
        return true;
    }

    @Override
    public UserResponseRecord login(UserRequestRecord userRequestRecord) {
        return new UserResponseRecord(token, defaultNickname);
    }

    @Override
    public String getUsernameByAuthentication(String authentication) {
        if (token.equals(authentication)) {
            return defaultUsername;
        } else {
            throw new LoomAgentRuntimeException("获取用户信息失败");
        }
    }

    @Override
    public String createToken(String username) {
        String sessionToken = UUID.randomUUID().toString();
        sessionCache.put(sessionToken, username);
        return sessionToken;
    }

    @Override
    public boolean validateToken(String token) {
        return sessionCache.get(token) != null;
    }

    @Override
    public void invalidateToken(String token) {
        sessionCache.evict(token);
    }

    @Override
    public String getUsernameByToken(String token) {
        Cache.ValueWrapper wrapper = sessionCache.get(token);
        return wrapper != null ? (String) wrapper.get() : null;
    }
}
