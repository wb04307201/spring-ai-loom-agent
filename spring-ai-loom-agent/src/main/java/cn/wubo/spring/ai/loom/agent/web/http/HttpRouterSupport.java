package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * 两个 router(以及任何后续 HTTP 写面)共用的辅助:
 * <ul>
 *   <li>取当前登录用户(只从会话,绝不从请求参数)</li>
 *   <li>按 username 构造 per-user 引擎(mirror {@code DefaultHttpTool.engineFor})</li>
 *   <li>凭据脱敏(token / password / value → {@code ***})</li>
 * </ul>
 *
 * <p>包私有,设计为 router 的内部辅助 —— 不暴露给消费者。
 */
final class HttpRouterSupport {

    static final String MASK = "***";

    private HttpRouterSupport() {}

    /** username 只从会话取,绝不从请求参数取 —— 否则构成横向越权(spec §4.3)。 */
    static String currentUsername() {
        return UserContextHolder.getCurrentUser();
    }

    /**
     * 按 username 构造 per-user {@link HttpEngine}。与
     * {@code DefaultHttpTool.engineFor} 字节级一致 —— 内部工具模式与 REST
     * 写面必须走同一构造逻辑,否则两壳会漂移(Task 8 双模契约测试保证)。
     */
    static HttpEngine engineFor(String username, LoomAgentProperties props) {
        LoomAgentProperties.HttpProperty p = props.getHttp();
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);   // 与 DefaultHttpTool.engineFor 保持一致(spec §4.4)
        cfg.setAllowedDomains(new LinkedHashSet<>(p.getAllowedDomains()));
        cfg.setMaxResponseSizeBytes(p.getMaxResponseSizeBytes());
        cfg.setMaxBatchConcurrency(p.getMaxBatchConcurrency());
        cfg.setMaxRequestBodyBytes(p.getMaxRequestBodyBytes());
        cfg.setHistoryMaxEntriesPerSystem(p.getHistoryMaxEntriesPerSystem());
        return new HttpEngine(LoomPaths.userHttpDir(props.getUsersBasePath(), username), cfg);
    }

    /**
     * 凭据脱敏:auth 里的 token / password / value 一律替换为 ***。
     * 读接口即便 LLM 走不到,也会被 admin UI 读取 —— 不能把凭据原文吐给浏览器。
     *
     * <p>{@code username} / {@code keyName} 是 metadata(name 不是 secret),保留原值。
     */
    static Map<String, Object> maskProfile(Profile p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", p.getName());
        out.put("description", p.getDescription());
        out.put("baseUrl", p.getBaseUrl());
        out.put("allowedDomains", p.getAllowedDomains());
        out.put("timeoutMs", p.getTimeoutMs());
        if (p.getAuth() == null) {
            out.put("auth", null);
        } else {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("type", p.getAuth().getType());
            a.put("token", mask(p.getAuth().getToken()));
            a.put("username", p.getAuth().getUsername());
            a.put("password", mask(p.getAuth().getPassword()));
            a.put("keyName", p.getAuth().getKeyName());
            a.put("value", mask(p.getAuth().getValue()));
            out.put("auth", a);
        }
        return out;
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? null : MASK;
    }
}
