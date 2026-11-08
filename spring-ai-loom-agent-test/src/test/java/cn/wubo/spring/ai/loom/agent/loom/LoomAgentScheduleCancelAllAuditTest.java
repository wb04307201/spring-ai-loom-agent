package cn.wubo.spring.ai.loom.agent.loom;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskInfo;
import cn.wubo.flex.schedule.core.TaskLimits;
import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleExecutionRepository;
import cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository;
import cn.wubo.spring.ai.loom.agent.schedule.IScheduleTool;
import cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord;
import cn.wubo.spring.ai.loom.agent.schedule.ScheduleExecutionProperties;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * cancel-all 审计保留回归锁（2026-11-08 b 修复）。
 *
 * <p><b>缺陷背景</b>：{@code POST /schedule/by-conversation/{conv}/cancel-all}
 * 在取消运行时任务、删除 {@code loom_scheduled_task} 声明行（防重启复活，必须删）
 * 之外，还删除了 {@code loom_schedule_execution} 执行审计行。这与
 * V1.0 schema 注释明文设计（"one_shot 取消 / 过期会删声明行,<b>但执行历史保留</b>"）
 * 相矛盾，且与单条取消路径（LLM 工具 / REST cancel 均不删执行行）行为不一致。
 * 后果：cancel-all 之后 admin 流水页的执行审计永久消失（实测
 * e2e-sched2 会话 9 条执行行被删，SCHEDULE_FIRE 归零）。
 *
 * <p><b>修复语义</b>：cancel-all 只取消运行时任务 + 删声明行；执行审计行保留，
 * 由既有的按天清理任务（{@code ScheduleExecutionCleanup#deleteOlderThan}）
 * 与会话删除级联（{@code deleteByUserAndConversation} 在 conversation-delete
 * 路径的调用）统一管理生命周期。
 *
 * <p><b>变异自证</b>：把 cancel-all 里的 execRepo 删除调用加回来，本用例必须变红。
 */
@DisplayName("schedule cancel-all 审计保留（不删 loom_schedule_execution）")
class LoomAgentScheduleCancelAllAuditTest {

    private FlexScheduledTaskService flex;
    private ILoomScheduleTriggerRepository triggerRepo;
    private ILoomScheduleExecutionRepository execRepo;
    private RouterFunction<ServerResponse> router;

    private static final String USER = "alice";
    private static final String CONV = "conv-9";
    private static final String TASK = "loom-sched-" + USER + "-" + CONV + "-daily";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        flex = mock(FlexScheduledTaskService.class);
        triggerRepo = mock(ILoomScheduleTriggerRepository.class);
        execRepo = mock(ILoomScheduleExecutionRepository.class);
        ObjectProvider<TaskLimits> limitsProvider = mock(ObjectProvider.class);
        when(limitsProvider.getIfAvailable()).thenReturn(TaskLimits.DISABLED);

        router = new LoomAgentConfiguration.ScheduleConfiguration().loomAgentScheduleRouter(
                flex, triggerRepo, execRepo, limitsProvider,
                mock(IScheduleTool.class), new ScheduleExecutionProperties());

        UserContextHolder.setCurrentUser(USER);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private ServerResponse route(String method, String path) throws Exception {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(method, path);
        servletRequest.setRequestURI(path);
        servletRequest.setServletPath(path);
        servletRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        servletRequest.setContent("{}".getBytes(StandardCharsets.UTF_8));
        ServerRequest request = ServerRequest.create(servletRequest,
                List.of(new MappingJackson2HttpMessageConverter()));
        return router.route(request).orElseThrow().handle(request);
    }

    private void givenOneOwnedLiveTask() {
        when(flex.listTasks()).thenReturn(List.of(
                new TaskInfo(TASK, "FIXED_RATE", "PT10M/PT0S")));
        when(triggerRepo.findByName(TASK)).thenReturn(Optional.of(
                new LoomScheduleTriggerRecord(
                        TASK, LoomScheduleTriggerRecord.TYPE_FIXED_RATE,
                        null, 600L, null, null, "p",
                        USER, CONV, false, Instant.EPOCH, Instant.EPOCH)));
        when(triggerRepo.deleteAllForConversation(USER, CONV)).thenReturn(1);
    }

    @Test
    @DisplayName("cancel-all：取消运行时任务 + 删声明行，但绝不删执行审计行")
    void cancelAll_keepsExecutionAuditRows() throws Exception {
        givenOneOwnedLiveTask();

        ServerResponse response = route("POST",
                "/spring/ai/loom/schedule/by-conversation/" + CONV + "/cancel-all");

        assertThat(response.statusCode().value()).isEqualTo(200);
        // 该做的照做：运行时取消 + 声明行删除（防重启复活）
        verify(flex).cancel(TASK);
        verify(triggerRepo).deleteAllForConversation(USER, CONV);
        // 不该做的：执行审计行保留（生命周期归 ScheduleExecutionCleanup / 会话删除级联）
        verify(execRepo, never()).deleteByUserAndConversation(anyString(), anyString());
        verifyNoInteractions(execRepo);
    }

    @Test
    @DisplayName("cancel-all 响应体不再包含 execRowsDeleted 字段")
    void cancelAll_responseHasNoExecRowsDeleted() throws Exception {
        givenOneOwnedLiveTask();

        ServerResponse response = route("POST",
                "/spring/ai/loom/schedule/by-conversation/" + CONV + "/cancel-all");

        Object body = ((org.springframework.web.servlet.function.EntityResponse<?>) response).entity();
        assertThat(body).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> map = (java.util.Map<String, Object>) body;
        assertThat(map).containsKeys("cancelled", "rowsDeleted");
        assertThat(map)
                .as("审计行不再被 cancel-all 删除，响应字段随之移除")
                .doesNotContainKey("execRowsDeleted");
    }
}
