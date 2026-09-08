package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.askuser.AskUserLogRecord;
import cn.wubo.spring.ai.loom.agent.askuser.IAskUserLogQuery;
import cn.wubo.spring.ai.loom.agent.testutil.LoomAgentTestUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §2 ask-logs 路由测试(真 router + 假 IAskUserLogQuery,无 Spring 上下文 ——
 * AskUserRouterTest / AdminRouterSpotTest 先例)。
 * admin 门禁由 AuthenticationFilter + adminPathPatterns 在真实容器层负责,
 * 本测试只锁路由行为(limit 解析 / username 透传 / 非法 limit 400)。
 */
@DisplayName("admin ask-logs 路由")
class AskUserLogRouterTest {

    private static AskUserLogRecord sample() {
        return new AskUserLogRecord(1L, "conv-1", "alice", "用哪种数据库?", "数据库选型",
                "MySQL", "ANSWERED", 42_000L, Instant.parse("2026-09-08T01:02:03Z"));
    }

    private RouterFunction<ServerResponse> routerWith(IAskUserLogQuery query) {
        return new LoomAgentConfiguration.WebConfiguration().loomAgentAskLogRouter(query);
    }

    @Test
    void defaultLimitIs50AndNoUsernameFilter() throws Exception {
        AtomicInteger seenLimit = new AtomicInteger();
        AtomicReference<String> seenUser = new AtomicReference<>();
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> {
            seenLimit.set(limit);
            seenUser.set(username);
            return List.of(sample());
        });
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenLimit.get()).isEqualTo(50);
        assertThat(seenUser.get()).isNull();
    }

    @Test
    void limitAndUsernameParamsPassThrough() throws Exception {
        AtomicInteger seenLimit = new AtomicInteger();
        AtomicReference<String> seenUser = new AtomicReference<>();
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> {
            seenLimit.set(limit);
            seenUser.set(username);
            return List.of();
        });
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs?limit=10&username=alice", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(200);
        assertThat(seenLimit.get()).isEqualTo(10);
        assertThat(seenUser.get()).isEqualTo("alice");
    }

    @Test
    void nonNumericLimitIs400() throws Exception {
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> List.of());
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs?limit=abc", null);
        assertThat(resp).isNotNull();
        assertThat(resp.statusCode().value()).isEqualTo(400);
    }

    @Test
    void bodyCarriesParsedRecord() throws Exception {
        RouterFunction<ServerResponse> router = routerWith((limit, username) -> List.of(sample()));
        ServerResponse resp = LoomAgentTestUtil.safeRoute(router, "GET",
                "/spring/ai/loom/admin/ask-logs", null);
        assertThat(resp).isNotNull();
        // EntityResponse body 即 record 列表(字段名 = 组件名,Jackson 序列化)
        assertThat(resp).isInstanceOf(org.springframework.web.servlet.function.EntityResponse.class);
        Object body = ((org.springframework.web.servlet.function.EntityResponse<?>) resp).entity();
        assertThat(body).isInstanceOf(List.class);
        assertThat((List<?>) body).hasSize(1);
        assertThat(((List<?>) body).get(0)).isInstanceOf(AskUserLogRecord.class);
    }
}
