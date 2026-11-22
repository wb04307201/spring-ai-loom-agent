package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.LoomAgentTestApplication;
import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.SubTaskRegistry;
import cn.wubo.spring.ai.loom.agent.tool.ILoomChatSteeringRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DELETE spring/ai/loom/conversation/{id}} 归属守卫(R33,2026-11-22 final review)
 * 的真库端到端回归锁。
 *
 * <p><b>缺陷(R33,安全)</b>:原 DELETE handler 无条件跑
 * {@code cleanupConversationResources(...)} 后才 {@code deleteById}(username-scoped)。
 * cleanup 里 {@code registry.killAllByConversation(convId)} 与
 * {@code loomChatSteeringRepository.deleteByConversation(convId)} 均<b>只按
 * conversation_id 匹配</b> ⇒ 任何登录用户持他人 conv UUID 即可:杀其活跃子任务 +
 * 清空其 steering 注入审计行(跨用户破坏;R23 引入审计级联时扩大了本已存在的缺口)。
 *
 * <p><b>修复</b>:归属校验(userConversation.exists)前置到 cleanup 之前,镜像 GET/steer
 * 的 BUG-12a 模式;跨用户 403 结构化错误体。软删本身 username-scoped,守卫后行为闭环。
 *
 * <p><b>本锁钉住的形态</b>(真 H2,两用户):
 * <ol>
 *   <li>跨用户 DELETE → 403,且受害者 steering 审计行<b>逐条存活</b>、活跃子任务<b>未被杀</b>、
 *       user_conversation 行<b>未被软删</b>(守卫必须先于 cleanup —— 只断言 403 状态码不够,
 *       破坏副作用在旧代码里发生在返回之前);</li>
 *   <li>owner DELETE → 200,级联照常:审计行清零、子任务 CANCELLED、映射软删。</li>
 * </ol>
 * 变异:去掉路由守卫 ⇒ 用例① 403 断言红(且子任务被杀);把守卫放到 cleanup 之后 ⇒
 * ①的状态码绿但 steering/子任务副作用红。
 */
@SpringBootTest(classes = LoomAgentTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@DisplayName("R33:DELETE /conversation/{id} 归属守卫(真库跨用户 403 + 受害者资源存活 + owner 级联照常)")
class ConversationDeleteOwnershipIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ILoomChatSteeringRepository steeringRepository;

    @Autowired
    private SubTaskRegistry subTaskRegistry;

    private static final String ADMIN = "wb04307201";
    private static final String PASS = "123456";

    private String createdVictim;
    private String createdConvId;
    private String createdTaskId;

    @AfterEach
    void cleanup() {
        // 共享 SpringBootTest 上下文会跨 IT 类存活:尽力清干净受害者的一切足迹
        // (内存子任务 + 三张真库表 + 账号),避免污染别的用例。
        if (createdTaskId != null && createdVictim != null) {
            try {
                subTaskRegistry.kill(createdVictim, createdTaskId);
            } catch (Exception ignore) {
            }
        }
        if (createdConvId != null) {
            try {
                jdbcTemplate.update("DELETE FROM loom_chat_steering WHERE conversation_id = ?", createdConvId);
                jdbcTemplate.update("DELETE FROM loom_subtask_history WHERE conversation_id = ?", createdConvId);
                jdbcTemplate.update("DELETE FROM user_conversation WHERE conversation_id = ?", createdConvId);
            } catch (Exception ignore) {
            }
        }
        if (createdVictim != null) {
            try {
                String adminCookie = login(ADMIN, PASS);
                rest.exchange("http://localhost:" + port + "/spring/ai/loom/admin/users/" + createdVictim,
                        HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(adminCookie)), Boolean.class);
            } catch (Exception ignore) {
                // 清理尽力而为;受害者数据以随机后缀隔离,不污染其它用例
            }
        }
        createdVictim = null;
        createdConvId = null;
        createdTaskId = null;
    }

    private String login(String username, String password) {
        ResponseEntity<Map> r = rest.postForEntity(
                "http://localhost:" + port + "/spring/ai/loom/user/login",
                new HttpEntity<>(Map.of("username", username, "password", password), jsonHeaders(null)),
                Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("登录应成功:" + username).isTrue();
        List<String> cookies = r.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull().isNotEmpty();
        return cookies.get(0).split(";")[0];
    }

    private HttpHeaders jsonHeaders(String cookie) {
        HttpHeaders h = new HttpHeaders();
        h.set("Content-Type", "application/json; charset=UTF-8");
        if (cookie != null) h.set("Cookie", cookie);
        return h;
    }

    /** 受害者建会话 + steering 审计行 + 活跃子任务;返回 conversationId。 */
    private String seedVictimAssets() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        createdVictim = "r33victim" + suffix;
        String adminCookie = login(ADMIN, PASS);
        // 建用户(成功体是裸 Boolean,非 JSON 对象)
        ResponseEntity<Boolean> created = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/admin/users",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("username", createdVictim, "nickname", "R33 受害者",
                        "password", PASS, "type", "USER"), jsonHeaders(adminCookie)),
                Boolean.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("创建受害者用户").isTrue();
        assertThat(created.getBody()).as("createUser 响应 true").isTrue();

        String victimCookie = login(createdVictim, PASS);
        // 受害者真实建会话(走路由,拿到归属真实的 conversationId)
        ResponseEntity<ConversationRecord> conv = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/user-conversations",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "r33victim-conv"), jsonHeaders(victimCookie)),
                ConversationRecord.class);
        assertThat(conv.getStatusCode().is2xxSuccessful()).as("受害者建会话").isTrue();
        String convId = conv.getBody().conversationId();
        assertThat(convId).isNotBlank();
        createdConvId = convId;

        // steering 审计行(R23 级联清理目标之一)
        steeringRepository.record(convId, createdVictim, "插话审计 🚀", Instant.now(), 1);
        steeringRepository.record(convId, createdVictim, "第二条插话", Instant.now(), 2);

        // 活跃子任务(内存 registry;cleanup 的 killAllByConversation 只按 convId 匹配)
        String taskId = subTaskRegistry.register(createdVictim, convId, "长任务");
        createdTaskId = taskId;
        CompletableFuture<?> future = new CompletableFuture<>();
        subTaskRegistry.attachFuture(taskId, future);
        return convId;
    }

    @Test
    @DisplayName("跨用户 DELETE → 403 且受害者 steering 行/子任务/会话映射全部存活(守卫先于 cleanup)")
    void crossUserDeleteIsRejectedAndVictimResourcesSurvive() {
        String convId = seedVictimAssets();
        String attackerCookie = login(ADMIN, PASS); // admin 不绕过(strict RBAC 哲学),同样 403

        // String.class:旧缺陷形态(200 + 裸 Boolean)与新形态(403 + JSON 对象)都能提取,
        // 断言落在状态码上 —— 变异(去掉守卫)得到的是干净的断言红而非 extract 异常。
        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + convId,
                HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(attackerCookie)), String.class);

        assertThat(resp.getStatusCode().value())
                .as("跨用户删除必须 403(镜像 GET/steer 的 BUG-12a 归属语义)").isEqualTo(403);
        assertThat(resp.getBody())
                .as("结构化错误体,前端可区分 403 与网络故障")
                .contains("forbidden").contains("403");

        // 核心安全断言:破坏性副作用一律未发生
        assertThat(steeringRepository.findByConversationId(convId))
                .as("R33:受害者 steering 审计行必须逐条存活(旧代码这里已被 deleteByConversation 清空)")
                .hasSize(2);
        List<SubTaskRegistry.SubTaskRecord> active =
                subTaskRegistry.listActiveByConversation(createdVictim, convId);
        assertThat(active)
                .as("R33:受害者活跃子任务未被跨用户 killAllByConversation 杀掉")
                .hasSize(1);
        assertThat(active.get(0).status()).isEqualTo(SubTaskStatus.RUNNING);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_conversation where conversation_id = ? and deleted_at is null",
                Integer.class, convId))
                .as("受害者会话映射未被软删").isEqualTo(1);
    }

    @Test
    @DisplayName("owner DELETE → 200 且级联清理照常执行(审计行清零、子任务 CANCELLED、映射软删)")
    void ownerDeleteStillRunsCascadeCleanup() {
        String convId = seedVictimAssets();
        String victim = createdVictim;
        createdVictim = null; // 正路径自己收尾,不再走 @AfterEach 的 admin 删号(会话已软删)

        String victimCookie = login(victim, PASS);
        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + convId,
                HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(victimCookie)), String.class);

        assertThat(resp.getStatusCode().value()).as("owner 删除照常 200").isEqualTo(200);
        assertThat(steeringRepository.findByConversationId(convId))
                .as("R23 级联:审计行随会话消失").isEmpty();
        List<SubTaskRegistry.SubTaskRecord> remainingActive =
                subTaskRegistry.listActiveByConversation(victim, convId);
        assertThat(remainingActive).as("owner 删除杀自己会话的子任务").isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from user_conversation where conversation_id = ? and deleted_at is null",
                Integer.class, convId))
                .as("会话映射已软删").isZero();

        // 守卫不破坏幂等:再删一次 → exists 已 false → 403(而非 500/静默 200)
        ResponseEntity<String> again = rest.exchange(
                "http://localhost:" + port + "/spring/ai/loom/conversation/" + convId,
                HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(victimCookie)), String.class);
        assertThat(again.getStatusCode().value())
                .as("软删后重复删除按跨用户同款守卫拒绝(exists 过滤 deleted_at)").isEqualTo(403);

        // 清理受害者账号
        String adminCookie = login(ADMIN, PASS);
        rest.exchange("http://localhost:" + port + "/spring/ai/loom/admin/users/" + victim,
                HttpMethod.DELETE, new HttpEntity<>(jsonHeaders(adminCookie)), Boolean.class);
    }
}
