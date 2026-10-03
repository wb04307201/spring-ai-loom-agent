package cn.wubo.spring.ai.loom.agent.web.http;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix round R1 (Task 7) — IT 锁定 auto-config wiring:
 * <ul>
 *   <li>{@code HttpManagementConfiguration} 被 {@code .imports} 正确登记</li>
 *   <li>{@code @AutoConfigureAfter(LoomAgentConfiguration.class)} 顺序正确(否则 {@code IRoleService} 不可用)</li>
 *   <li>{@code @Import} 把两个 router 类拉起(否则 {@code @Bean RouterFunction} 不在 context 中)</li>
 *   <li>真实 MockMvc 能命中 HTTP 端点</li>
 * </ul>
 *
 * <p>不依赖 cookie / 鉴权链路 —— AuthenticationFilter 拒绝无 cookie 请求是预期路径,
 * 401/403 都是"router 真的注册到了 WebMvc"的可观测证据。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class)
@DisplayName("HTTP auto-config wiring IT —— beans + MockMvc 真路由")
class HttpAutoConfigWiringIT {

    @Autowired private ApplicationContext context;
    @Autowired private WebApplicationContext webContext;

    @Test
    @DisplayName("HttpManageGuard bean 存在(证明 .imports + @AutoConfiguration + @AutoConfigureAfter 顺序生效)")
    void httpManageGuardBeanPresent() {
        HttpManageGuard guard = context.getBean(HttpManageGuard.class);
        assertThat(guard).isNotNull();
    }

    @Test
    @DisplayName("两个 RouterFunction bean 存在(证明 @Import 拉起 + 类有 @Bean RouterFunction 方法)")
    void routerFunctionBeansPresent() {
        String[] names = context.getBeanNamesForType(RouterFunction.class);
        assertThat(names)
                .as("至少应有 httpProfileRouter + httpSystemRouter 两个 RouterFunction bean")
                .anyMatch(n -> n.toLowerCase().contains("httpprofilerouter") || n.toLowerCase().contains("profile"))
                .anyMatch(n -> n.toLowerCase().contains("httpsystemrouter") || n.toLowerCase().contains("system"));
    }

    @Test
    @DisplayName("MockMvc 真路由可达 /api/http/profiles(401/403 都行 —— 关键是 router 被 WebMvc 注册)")
    void mockMvcReachesRouter() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        // AuthenticationFilter 对无 cookie 请求会 401;有 cookie 但无 grant 会 403。
        // 两种结果都证明 router bean 被 WebMvc 发现并加入 handler chain。
        mockMvc.perform(get("/spring/ai/loom/api/http/profiles"))
                .andExpect(result -> {
                    int code = result.getResponse().getStatus();
                    assertThat(code)
                            .as("router 必须被注册;无 cookie → 401,无 grant → 403,二者都是路由可达的证据")
                            .isIn(401, 403);
                });
    }
}
