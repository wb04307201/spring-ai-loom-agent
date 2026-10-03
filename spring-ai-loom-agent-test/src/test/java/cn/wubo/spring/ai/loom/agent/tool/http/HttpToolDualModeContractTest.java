package cn.wubo.spring.ai.loom.agent.tool.http;

import cn.wubo.loom.file.core.LoomPaths;
import cn.wubo.loom.http.core.HttpConfig;
import cn.wubo.loom.http.core.HttpEngine;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双模一致性契约:内部工具壳与直接调引擎必须给出相同结果。
 *
 * <p>两个壳是同一份 core 的两个薄壳,若行为漂移,jar 用户与 loom 用户会得到
 * 不同答案 —— 这类偏差极难在集成期发现,故在此逐字断言。
 */
@DisplayName("双模一致性:IHttpTool 与 HttpEngine 结果逐字一致")
class HttpToolDualModeContractTest {

    private static ToolContext ctxFor(String username) {
        return new ToolContext(Map.of("username", username));
    }

    @Test
    @DisplayName("listEndpoints:两壳输出逐字相同")
    void listEndpointsIdentical(@TempDir Path tmp) {
        // ---- direct engine ----
        HttpConfig cfg = new HttpConfig();
        cfg.setAllowedDomains(new LinkedHashSet<>(List.of("localhost")));
        Path root = LoomPaths.userHttpDir(tmp.toString(), "alice");

        HttpEngine engine = new HttpEngine(root, cfg);
        cn.wubo.loom.http.core.system.System sys = new cn.wubo.loom.http.core.system.System();
        sys.setName("svc");
        sys.setBaseUrl("http://localhost:8080");
        engine.registerSystem(sys);

        // ---- IHttpTool shell ----
        LoomAgentProperties.HttpProperty prop = new LoomAgentProperties.HttpProperty();
        prop.setAllowedDomains(List.of("localhost"));
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), prop);

        String a = tool.listEndpoints("svc", null, null, ctxFor("alice"));
        String b = engine.listEndpoints("svc", null, null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("未注册 system:两壳都返回结构化错误,不抛异常")
    void unknownSystemSameError(@TempDir Path tmp) {
        HttpConfig cfg = new HttpConfig();
        Path root = LoomPaths.userHttpDir(tmp.toString(), "alice");
        HttpEngine engine = new HttpEngine(root, cfg);

        LoomAgentProperties.HttpProperty prop = new LoomAgentProperties.HttpProperty();
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), prop);

        String a = tool.listEndpoints("nope", null, null, ctxFor("alice"));
        String b = engine.listEndpoints("nope", null, null);
        assertThat(a).isEqualTo(b);
        assertThat(a).contains("error");
    }

    @Test
    @DisplayName("listEndpoints 带 tag/source 过滤:两壳输出逐字相同")
    void listEndpointsWithFiltersIdentical(@TempDir Path tmp) {
        // pre-populate
        HttpConfig cfg = new HttpConfig();
        cfg.setAllowedDomains(new LinkedHashSet<>(List.of("localhost")));
        Path root = LoomPaths.userHttpDir(tmp.toString(), "alice");
        HttpEngine engine = new HttpEngine(root, cfg);
        cn.wubo.loom.http.core.system.System sys = new cn.wubo.loom.http.core.system.System();
        sys.setName("svc");
        sys.setBaseUrl("http://localhost:8080");
        engine.registerSystem(sys);
        // add a manual endpoint
        String addResult = engine.addEndpoint("svc", "GET", "/x",
                null, null, null, "summary", null, List.of("tag-a"));
        assertThat(addResult).contains("\"path\":\"/x\"");

        LoomAgentProperties.HttpProperty prop = new LoomAgentProperties.HttpProperty();
        prop.setAllowedDomains(List.of("localhost"));
        DefaultHttpTool tool = new DefaultHttpTool(tmp.toString(), prop);

        // both filters
        String a1 = tool.listEndpoints("svc", "tag-a", "manual", ctxFor("alice"));
        String b1 = engine.listEndpoints("svc", "tag-a", "manual");
        assertThat(a1).isEqualTo(b1);

        // tag that does not match
        String a2 = tool.listEndpoints("svc", "no-such-tag", null, ctxFor("alice"));
        String b2 = engine.listEndpoints("svc", "no-such-tag", null);
        assertThat(a2).isEqualTo(b2);

        // null filters
        String a3 = tool.listEndpoints("svc", null, null, ctxFor("alice"));
        String b3 = engine.listEndpoints("svc", null, null);
        assertThat(a3).isEqualTo(b3);
    }
}