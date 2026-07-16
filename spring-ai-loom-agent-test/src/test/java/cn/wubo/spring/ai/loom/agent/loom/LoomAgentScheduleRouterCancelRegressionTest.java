package cn.wubo.spring.ai.loom.agent.loom;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Regression tests pinning the dual-write contract of
 * {@code POST /spring/ai/loom/schedule/cancel} (the REST endpoint the UI calls).
 *
 * <p>Bug history: Phase 3 wired {@code DefaultScheduleTool.cancelSchedule} (the
 * LLM-tool path) to delete the corresponding row from
 * {@code loom_scheduled_task}, but the UI-direct REST route only called
 * {@code FlexScheduledTaskService.cancel(name)} and skipped the repository
 * delete. Result: cancelling via UI left a "ghost" row that
 * {@code ScheduleRestoreListener} would resurrect on the next restart. This test
 * exercises the same helper that the router delegates to so a future refactor
 * cannot silently drop the persistence-side delete.</p>
 */
class LoomAgentScheduleRouterCancelRegressionTest {

    @Test
    void handleScheduleCancel_invokesBothFlexServiceCancelAndRepoDelete() {
        FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
        ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
        String fullName = "loom-sched-alice-conv-1-remind";

        boolean handled = LoomAgentConfiguration.handleScheduleCancel(
                fullName, flex, repo, LoggerFactory.getLogger(getClass()));

        assertThat(handled).isTrue();
        verify(flex).cancel(fullName);
        verify(repo).delete(fullName);
        verifyNoMoreInteractions(flex, repo);
    }

    @Test
    void handleScheduleCancel_withNullName_skipsBothLayers() {
        FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
        ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);

        boolean handled = LoomAgentConfiguration.handleScheduleCancel(
                null, flex, repo, LoggerFactory.getLogger(getClass()));

        assertThat(handled).isFalse();
        verifyNoInteractions(flex, repo);
    }

    @Test
    void handleScheduleCancel_swallowsRepoFailure_butStillCancels() {
        FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
        ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
        when(repo.delete(anyString())).thenThrow(new RuntimeException("db boom"));
        String fullName = "loom-sched-alice-conv-1-orphan";

        // Must not propagate — the user-facing cancel returns true even if
        // persistence cleanup fails (the live task is the part that matters).
        boolean handled = LoomAgentConfiguration.handleScheduleCancel(
                fullName, flex, repo, LoggerFactory.getLogger(getClass()));

        assertThat(handled).isTrue();
        verify(flex).cancel(fullName);
        verify(repo).delete(fullName);
    }

    @Test
    void handleScheduleCancel_withUnknownName_stillDeletesRepoByName() {
        // Best-effort cleanup: if a stray row exists for an unknown name, this
        // pin enforces that REST cancel still issues the repo delete so the
        // persistence layer is consistent with the user request.
        FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
        ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
        String fullName = "loom-sched-alice-conv-1-stray";

        LoomAgentConfiguration.handleScheduleCancel(
                fullName, flex, repo, LoggerFactory.getLogger(getClass()));

        verify(flex).cancel(fullName);
        verify(repo).delete(fullName);
    }
}
