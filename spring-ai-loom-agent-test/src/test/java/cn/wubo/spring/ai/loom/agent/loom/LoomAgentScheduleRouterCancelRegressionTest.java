package cn.wubo.spring.ai.loom.agent.loom;

import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.spring.ai.loom.agent.LoomAgentConfiguration;
import cn.wubo.spring.ai.loom.agent.schedule.ILoomScheduleTriggerRepository;
import cn.wubo.spring.ai.loom.agent.schedule.LoomScheduleTriggerRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

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
 *
 * <p>BUG-13 (2026-07-19) layered a cross-user ownership check on top: before
 * firing the cancel, {@code handleScheduleCancel} looks up the row and refuses
 * unless the row exists AND is owned by the current caller
 * ({@link UserContextHolder#getCurrentUser()}). These tests therefore establish
 * a caller and a matching owned row before asserting the dual-write, and pin the
 * refusal path for an unknown/unowned name.</p>
 */
class LoomAgentScheduleRouterCancelRegressionTest {

 @AfterEach
 void clearCaller() {
 UserContextHolder.clear();
 }

 /** Minimal owned schedule row for the BUG-13 ownership guard. */
 private static LoomScheduleTriggerRecord ownedRow(String taskName, String username) {
 return new LoomScheduleTriggerRecord(
 taskName, LoomScheduleTriggerRecord.TYPE_FIXED_DELAY,
 null, 600L, null, null, "p",
 username, "conv-1", false,
 Instant.EPOCH, Instant.EPOCH);
 }

 @Test
 void handleScheduleCancel_invokesBothFlexServiceCancelAndRepoDelete() {
 FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
 ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
 String fullName = "loom-sched-alice-conv-1-remind";
 UserContextHolder.setCurrentUser("alice");
 when(repo.findByName(fullName)).thenReturn(Optional.of(ownedRow(fullName, "alice")));

 boolean handled = LoomAgentConfiguration.handleScheduleCancel(
 fullName, flex, repo, LoggerFactory.getLogger(getClass()));

 assertThat(handled).isTrue();
 verify(repo).findByName(fullName);
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
 String fullName = "loom-sched-alice-conv-1-orphan";
 UserContextHolder.setCurrentUser("alice");
 when(repo.findByName(fullName)).thenReturn(Optional.of(ownedRow(fullName, "alice")));
 when(repo.delete(anyString())).thenThrow(new RuntimeException("db boom"));

 // Must not propagate — the user-facing cancel returns true even if
 // persistence cleanup fails (the live task is the part that matters).
 boolean handled = LoomAgentConfiguration.handleScheduleCancel(
 fullName, flex, repo, LoggerFactory.getLogger(getClass()));

 assertThat(handled).isTrue();
 verify(flex).cancel(fullName);
 verify(repo).delete(fullName);
 }

 @Test
 void handleScheduleCancel_withUnknownName_isRefused() {
 // BUG-13: an unknown/unowned name has no row to verify ownership against,
 // so REST cancel must REFUSE — never touching flex-schedule or issuing a
 // blind delete-by-name. (This replaces the pre-BUG-13 "best-effort delete
 // by name" contract, which could act on a row the caller doesn't own.)
 FlexScheduledTaskService flex = mock(FlexScheduledTaskService.class);
 ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
 String fullName = "loom-sched-alice-conv-1-stray";
 UserContextHolder.setCurrentUser("alice");
 when(repo.findByName(fullName)).thenReturn(Optional.empty());

 boolean handled = LoomAgentConfiguration.handleScheduleCancel(
 fullName, flex, repo, LoggerFactory.getLogger(getClass()));

 assertThat(handled).isFalse();
 verify(repo).findByName(fullName);
 verify(flex, never()).cancel(anyString());
 verify(repo, never()).delete(anyString());
 }

 @Test
 void handleScheduleHistoryOwnership_allowsOwnerOnly() {
 ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
 String fullName = "loom-sched-alice-conv-1-history";
 when(repo.findByName(fullName)).thenReturn(Optional.of(ownedRow(fullName, "alice")));
 UserContextHolder.setCurrentUser("alice");

 assertThat(LoomAgentConfiguration.handleScheduleHistoryOwnership(
 fullName, repo, LoggerFactory.getLogger(getClass()))).isTrue();
 }

 @Test
 void handleScheduleHistoryOwnership_rejectsForeignUnknownAndMissingCaller() {
 ILoomScheduleTriggerRepository repo = mock(ILoomScheduleTriggerRepository.class);
 String fullName = "loom-sched-alice-conv-1-history";
 when(repo.findByName(fullName)).thenReturn(Optional.of(ownedRow(fullName, "alice")));

 UserContextHolder.setCurrentUser("bob");
 assertThat(LoomAgentConfiguration.handleScheduleHistoryOwnership(
 fullName, repo, LoggerFactory.getLogger(getClass()))).isFalse();

 UserContextHolder.clear();
 assertThat(LoomAgentConfiguration.handleScheduleHistoryOwnership(
 fullName, repo, LoggerFactory.getLogger(getClass()))).isFalse();

 when(repo.findByName("unknown")).thenReturn(Optional.empty());
 UserContextHolder.setCurrentUser("alice");
 assertThat(LoomAgentConfiguration.handleScheduleHistoryOwnership(
 "unknown", repo, LoggerFactory.getLogger(getClass()))).isFalse();
 assertThat(LoomAgentConfiguration.handleScheduleHistoryOwnership(
 null, repo, LoggerFactory.getLogger(getClass()))).isFalse();
 }
}

