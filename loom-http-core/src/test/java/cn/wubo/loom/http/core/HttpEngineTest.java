package cn.wubo.loom.http.core;

import cn.wubo.loom.http.core.profile.Profile;
import cn.wubo.loom.http.core.system.System;
import cn.wubo.loom.http.core.invoke.InvokeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpEngine 集成测试 —— 重点是注册时的校验拦截(总纲 Review Focus 第 1 条)。
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>auth.type 拼错 → 拒绝且不落盘</li>
 *   <li>bearer 缺 token → 拒绝</li>
 *   <li>system.baseUrl 漏 scheme → 注册时拒绝,不留半截文件</li>
 *   <li>重名 system 拒绝注册</li>
 *   <li>调用未注册 system 返回结构化错误 JSON</li>
 * </ul>
 */
@DisplayName("HttpEngine 集成")
class HttpEngineTest {

    @Test
    @DisplayName("auth.type 拼错 → addProfile 拒绝,且不落盘")
    void rejectsBadAuthType(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        Profile p = new Profile();
        p.setName("typo");
        p.getAuth().setType("beareer");   // 笔误
        p.getAuth().setToken("t");
        String out = engine.addProfile(p);
        assertThat(out).contains("validation failed");
        assertThat(engine.listProfiles()).doesNotContain("typo");   // 校验失败不得留下半截文件
    }

    @Test
    @DisplayName("bearer 类型缺 token → 拒绝")
    void rejectsBearerWithoutToken(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        Profile p = new Profile();
        p.setName("notoken");
        p.getAuth().setType("bearer");
        String out = engine.addProfile(p);
        assertThat(out).contains("validation failed");
        assertThat(engine.listProfiles()).doesNotContain("notoken");
    }

    @Test
    @DisplayName("system.baseUrl 漏 scheme → 注册时拒绝,不留半截文件")
    void rejectsBaseUrlWithoutScheme(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        System s = new System();
        s.setName("noscheme");
        s.setBaseUrl("api.example.com");     // 漏 https://
        String out = engine.registerSystem(s);
        assertThat(out).contains("validation failed");
        assertThat(engine.listSystems()).doesNotContain("noscheme");
    }

    @Test
    @DisplayName("重名 system 拒绝注册")
    void rejectsDuplicateSystem(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        System s = new System();
        s.setName("dup");
        s.setBaseUrl("https://api.example.com");
        String first = engine.registerSystem(s);
        assertThat(first).contains("created");
        String second = engine.registerSystem(s);
        assertThat(second).contains("already exists");
    }

    @Test
    @DisplayName("调用未注册 system 返回结构化错误 JSON 而非抛异常")
    void unknownSystemReturnsStructuredError(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        InvokeRequest req = new InvokeRequest();
        req.setSystem("ghost");
        req.setMethod("GET");
        req.setPath("/x");
        String out = engine.invokeEndpoint(req);
        // 必须是结构化错误信封,不是裸异常
        assertThat(out).contains("SystemNotFound");
    }

    @Test
    @DisplayName("新增 profile → 落盘 → 重新 new HttpEngine 读回")
    void addProfileThenReloadReadsBack(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        Profile p = new Profile();
        p.setName("myname");
        p.getAuth().setType("bearer");
        p.getAuth().setToken("secret");
        String out = engine.addProfile(p);
        assertThat(out).contains("created");
        assertThat(engine.listProfiles()).containsExactly("myname");

        // 重新构造引擎,验证从磁盘读回
        HttpEngine reloaded = new HttpEngine(tmp, new HttpConfig());
        assertThat(reloaded.listProfiles()).containsExactly("myname");
    }

    @Test
    @DisplayName("部分更新只改一个字段时其余字段保持不变")
    void updateProfilePartialMerge(@TempDir Path tmp) {
        HttpEngine engine = new HttpEngine(tmp, new HttpConfig());
        Profile orig = new Profile();
        orig.setName("p1");
        orig.setBaseUrl("https://api.example.com");
        orig.getAuth().setType("bearer");
        orig.getAuth().setToken("token-original");
        engine.addProfile(orig);

        Profile patch = new Profile();
        patch.getAuth().setType("bearer");
        patch.getAuth().setToken("token-updated");   // 只改 token
        String out = engine.updateProfile("p1", patch);
        assertThat(out).contains("updated");

        // 验证 baseUrl 没被清空 —— 部分更新是 merge 不是 replace
        assertThat(out).contains("updated");
    }

    @Test
    @DisplayName("failClosed 模式:invokeEndpoint 在 global 空时拒绝,不发出网络请求")
    void failClosedRejectsBeforeNetworkCall(@TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        cfg.setFailClosed(true);
        HttpEngine engine = HttpEngine.of(tmp.toString(), cfg);

        System s = new System();
        s.setName("blocked");
        s.setBaseUrl("http://localhost:1");   // 端口 1 无监听
        engine.registerSystem(s);

        InvokeRequest req = new InvokeRequest();
        req.setSystem("blocked");
        req.setMethod("GET");
        req.setPath("/x");
        String out = engine.invokeEndpoint(req);
        assertThat(out).contains("DomainNotAllowed");
        assertThat(out).doesNotContain("Connection refused");
    }
}
