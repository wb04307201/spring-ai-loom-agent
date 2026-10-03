package cn.wubo.loom.http.core;

import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.security.DomainWhitelist;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import cn.wubo.loom.http.core.invoke.InvokeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 白名单 fail-closed 策略(spec §4.4 三态表)。
 *
 * <p>与 http-mcp 的行为差异：原实现在 global 为空时"不限制"(fail-open)。
 * 内部工具模式改为 fail-closed —— 部署未配置 allowedDomains = 未授权对外访问。
 */
@DisplayName("白名单 fail-closed 策略")
class HttpWhitelistPolicyTest {

    @Test
    @DisplayName("空白名单拒绝一切 —— fail-closed 的底层语义")
    void emptyAllowsNothing() {
        DomainWhitelist w = new DomainWhitelist(Set.of());
        assertThat(w.allows("example.com")).isFalse();
        assertThat(w.allows("169.254.169.254")).isFalse();
        assertThat(w.allows("localhost")).isFalse();
    }

    @Test
    @DisplayName("profile 收紧后不包含的域名被拒")
    void profileNarrowsGlobal() {
        // global 授权 .example.com 和 .partner.com,profile 收紧为 .partner.com
        // → effective 只保留 profile 中被 global 覆盖的 .partner.com。
        DomainWhitelist global = new DomainWhitelist(Set.of(".example.com", ".partner.com"));
        DomainWhitelist profile = new DomainWhitelist(Set.of(".partner.com"));
        DomainWhitelist effective = global.effectiveAllowed(profile);
        assertThat(effective.allows("api.example.com")).isFalse();   // profile 收紧,被拒
        assertThat(effective.allows("api.partner.com")).isTrue();     // profile 覆盖,放行
    }

    @Test
    @DisplayName("HttpConfig.failClosed 默认 false —— jar 侧保持 http-mcp 既有行为")
    void failClosedDefaultsOff() {
        assertThat(new HttpConfig().isFailClosed()).isFalse();
    }

    @Test
    @DisplayName("global 白名单为空 → 拒绝，且根本没发出网络请求")
    void emptyGlobalRejectsBeforeSendingRequest(@org.junit.jupiter.api.io.TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);      // 内部工具模式的取值；jar 侧默认 false 走 fail-open
        HttpEngine engine = HttpEngine.of(tmp.toString(), cfg);

        System s = new System();
        s.setName("svc");
        s.setBaseUrl("http://localhost:1");   // 端口 1 无监听：真发请求会得到连接错误
        engine.registerSystem(s);

        InvokeRequest req = new InvokeRequest();
        req.setSystem("svc");
        req.setMethod("GET");
        req.setPath("/x");

        String out = engine.invokeEndpoint(req);
        // 必须是白名单拒绝，而不是连接错误 —— 证明拒绝发生在发请求之前
        assertThat(out).contains("DomainNotAllowed");
        assertThat(out).doesNotContain("Connection refused");
    }

    @Test
    @DisplayName("failClosed=false（jar 默认）→ 空白名单退回 http-mcp 的 fail-open")
    void jarModeStaysFailOpen(@org.junit.jupiter.api.io.TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();   // failClosed 保持默认 false
        HttpEngine engine = HttpEngine.of(tmp.toString(), cfg);

        Profile p = new Profile();
        p.setName("only");
        p.getAuth().setType("bearer");   // 必填:ProfileValidator 对 type 做枚举校验
        p.getAuth().setToken("t");
        engine.addProfile(p);
        // profile 声明了白名单 → global 空时退回它（http-mcp 原行为）
        assertThat(engine.listProfiles()).containsExactly("only");
    }
}
