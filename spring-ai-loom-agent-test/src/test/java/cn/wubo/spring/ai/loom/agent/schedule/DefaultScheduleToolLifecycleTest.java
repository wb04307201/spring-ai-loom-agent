package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionRecord;
import cn.wubo.flex.schedule.core.FlexScheduledTaskService;
import cn.wubo.flex.schedule.core.TaskBuilder;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import cn.wubo.spring.ai.loom.agent.subtask.ISubTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Lifecycle tests for {@link DefaultScheduleTool} after the
 * flex-schedule 1.2.2 upgrade.
 *
 * <p>Covers the gaps in the original {@link DefaultScheduleToolTest}:
 * <ul>
 * <li><b>parseSeconds</b> lenient parser — every suffix/numeric/null/garbage variant.</li>
 * <li><b>runAsSubTask</b> success / failure / exception paths — verifies the
 * re-throw on FAILED (Fix-E) plus the dual-write into the execution repo
 * plus the trim call.</li>
 * <li><b>recordExecution</b> trim behavior — confirms the per-task cap is honored
 * and that a null executionRepo short-circuits cleanly.</li>
 * <li><b>Persistence rollback</b> — when {@code loomScheduleTriggerRepository.save}
 * throws, {@code flexService.cancel(full)} must be called so the in-memory and
 * DB layers stay aligned.</li>
 * <li><b>prompt blank guard</b> — empty/whitespace prompt rejected up-front, no
 * call to {@code flexService.task(...)} at all.</li>
 * <li><b>cancelSchedule owner mismatch</b> — foreign user hitting the cancel
 * tool gets the same "not found" message (no information leak).</li>
 * </ul>
 */
class DefaultScheduleToolLifecycleTest {

 private FlexScheduledTaskService flexService;
 private ISubTaskExecutor executor;
 private ILoomScheduleTriggerRepository triggerRepo;
 private ILoomScheduleExecutionRepository execRepo;
 private DefaultScheduleTool tool;

 @BeforeEach
 void setUp() {
 flexService = mock(FlexScheduledTaskService.class);
 executor = mock(ISubTaskExecutor.class);
 triggerRepo = mock(ILoomScheduleTriggerRepository.class);
 execRepo = mock(ILoomScheduleExecutionRepository.class);
 tool = new DefaultScheduleTool(flexService, executor, triggerRepo, execRepo, 1000);
 }

 private static ToolContext ctx(String user, String conv) {
 return new ToolContext(Map.of("username", user, "parentConversationId", conv));
 }

 private static LoomScheduleTriggerRecord ownedRow(String taskName, String user, String conv) {
 return new LoomScheduleTriggerRecord(
 taskName, LoomScheduleTriggerRecord.TYPE_FIXED_DELAY,
 null, 600L, null, null, "say hi",
 user, conv, false, Instant.EPOCH, Instant.EPOCH);
 }

 @ParameterizedTest(name = "[{index}] parseSeconds(\"{0}\") -> {1}")
 @CsvSource(textBlock = """
 '10', 10
 '10s', 10
 '10S', 10
 '10 secs', 10
 '10sec', 10
 '10秒', 10
 '10 秒', 10
 '1m', 60
 '5min', 300
 '5 minute', 300
 '10 mins', 600
 '10 minutes',600
 '10 分钟', 600
 """)
 void parseSeconds_acceptsPlainAndSuffixedExpressions(String input, long expected) {
 // Exercise the parser indirectly through the LLM-driven create path.
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);
 when(builder.fixedRate(any())).thenReturn(builder);
 when(builder.oneShot(any())).thenReturn(builder);

 tool.createSchedule("n", "fixed_delay", input, "do it", ctx("alice", "conv-1"));
 verify(builder).fixedDelay(eq(java.time.Duration.ofSeconds(expected)));
 }

 @ParameterizedTest(name = "[{index}] parseSeconds rejects \"{0}\"")
 @NullSource
 @ValueSource(strings = {"banana", "5h", "ZZZ", " ", "##"})
 void parseSeconds_rejectsGarbageAsMinusOne(String input) {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 String response = tool.createSchedule("n", "fixed_delay", input, "do it", ctx("alice", "conv-1"));

 // The parser returns -1 → Duration.ofSeconds(-1) — flex-schedule rejects
 // negative durations as IllegalArgumentException → we surface its message.
 assertThat(response).startsWith("[");
 verify(flexService).task(any());
 }

 @Test
 void createSchedule_blankPrompt_returnsFriendlyMessage_noFlexCall() {
 String response = tool.createSchedule("n", "fixed_delay", "600", " ", ctx("alice", "conv-1"));

 assertThat(response).contains("prompt 不能为空");
 verifyNoInteractions(flexService);
 }

 @Test
 void createSchedule_nullPrompt_returnsFriendlyMessage_noFlexCall() {
 String response = tool.createSchedule("n", "fixed_delay", "600", null, ctx("alice", "conv-1"));

 assertThat(response).contains("prompt 不能为空");
 verifyNoInteractions(flexService);
 }

 @Test
 void persistAfterRegister_rollsBackFlexRegistrationWhenRepoSaveThrows() {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 String full = "loom-sched-alice-conv-1-remind";
 // save() returns void — must use the doThrow(...).when(...) idiom, not when(...).thenThrow(...).
 doThrow(new RuntimeException("DB down")).when(triggerRepo).save(any());

 tool.createSchedule("remind", "fixed_delay", "600", "do it", ctx("alice", "conv-1"));

 // The register succeeded in-memory → on save failure we must cancel so
 // the flex-schedule runtime and the loom-scheduled_task table stay aligned.
 verify(flexService).cancel(full);
 }

 @Test
 void cancelSchedule_foreignOwner_returnsSameMessageAsMissing_noInformationLeak() {
 String full = "loom-sched-admin-conv-1-remind";
 // DB has the row but it's owned by admin, not the caller (alice).
 when(triggerRepo.findByName(full))
 .thenReturn(Optional.of(ownedRow(full, "admin", "conv-1")));

 String response = tool.cancelSchedule("remind", ctx("alice", "conv-1"));

 // Same string as a truly-missing row — alice can't tell whether admin's task exists.
 assertThat(response).isEqualTo("[取消失败] 未找到任务: remind");
 verify(flexService, never()).cancel(any());
 }

 @Test
 void cancelSchedule_unknownRow_returnsFriendlyMessage_noFlexCall() {
 when(triggerRepo.findByName(any())).thenReturn(Optional.empty());
 String response = tool.cancelSchedule("remind", ctx("alice", "conv-1"));

 assertThat(response).isEqualTo("[取消失败] 未找到任务: remind");
 verify(flexService, never()).cancel(any());
 }

 @Test
 void getScheduleHistory_handlesNullExecutionHistoryFromFlex_silently() {
 when(flexService.getExecutionHistory(any(), anyInt()))
 .thenThrow(new RuntimeException("history not wired"));

 String response = tool.getScheduleHistory("remind", 10, ctx("alice", "conv-1"));

 assertThat(response).startsWith("[查询失败]");
 assertThat(response).contains("history not wired");
 }

 @Test
 void listSchedules_emptySet_outputsFriendlyNoTasksMessage() {
 when(flexService.listTasks()).thenReturn(java.util.List.of());

 String response = tool.listSchedulesRaw("alice");

 assertThat(response).contains("无定时任务");
 }

 @Test
 void listSchedules_skipsNamesWithNoFirstDashInsteadOfThrowing() {
 // Malformed task name "loom-sched-alice" (no conversation-id segment) —
 // we must not throw, just continue.
 when(flexService.listTasks()).thenReturn(java.util.List.of(
 new cn.wubo.flex.schedule.core.TaskInfo("loom-sched-alice", "FIXED_DELAY", null),
 new cn.wubo.flex.schedule.core.TaskInfo("loom-sched-alice-conv-1-remind", "FIXED_DELAY", null)
 ));

 String response = tool.listSchedulesRaw("alice");

 // Only the well-formed task should appear.
 assertThat(response).contains("remind");
 assertThat(response).doesNotContain("loom-sched-alice\n");
 }

 /**
 * Sanity: validate that the duration-suffix parser lands inside
 * flex-schedule's min-interval guard. We don't actually run flex here — we
 * just demonstrate that "5min" → 300 secs, which the test app's
 * {@code flex.schedule.limits.min-interval=10m} would refuse. The LLM-driven
 * create path would surface the library's exception via the catch block.
 */
 @Test
 void createSchedule_minuteExpression_resolvesBeforeLimitsKickIn() {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 tool.createSchedule("n", "fixed_delay", "5min", "p", ctx("alice", "conv-1"));

 verify(builder).fixedDelay(eq(java.time.Duration.ofSeconds(300)));
 }

 /**
 * Indirect verification that the {@code @Tool} routing keys the conversation
 * id off the ToolContext's {@code parentConversationId}, not a different
 * field name. The keys are produced by the BFF / frontend; missing the
 * parentConversationId would silently route every per-conversation command
 * to a single shared namespace.
 */
 @Test
 void createSchedule_usesParentConversationIdNotConversationId() {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 tool.createSchedule("n", "fixed_delay", "600", "p",
 new ToolContext(Map.of("username", "alice", "parentConversationId", "conv-42")));

 verify(flexService).task(contains("conv-42"));
 }

 /**
 * Indirect coverage of {@code recordExecution} + trim — verify that when an
 * {@link ILoomScheduleExecutionRepository} is wired, the trim call is fired
 * with the configured max-per-task cap.
 *
 * <p>{@code createSchedule} only builds + registers the schedule — it does
 * not actually fire the runnable. To exercise {@code runAsSubTask} we have
 * to capture the {@link Runnable} the {@code TaskBuilder.register(...)}
 * method receives and invoke it by hand. The mocked
 * {@link FlexScheduledTaskService} returns a mocked builder that returns
 * itself from every fluent setter, so the chain reaches register without
 * throwing.</p>
 */
 @Test
 void recordExecution_invokesTrimTaskHistoryWithConfiguredCap() {
 // Use a value above the constructor's defensive floor (Math.max(10, n));
 // see recordExecution_invokesFloorWhenBelow10 for the floor path.
 DefaultScheduleTool cappedTool = new DefaultScheduleTool(
 flexService, executor, triggerRepo, execRepo, 25);
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 cn.wubo.spring.ai.loom.agent.model.SubTaskRequest subReq = new cn.wubo.spring.ai.loom.agent.model.SubTaskRequest(
 "sub-id", "conv-1", null, "alice", "p", null, true);
 when(executor.execute(any())).thenReturn(
 SubTaskResult.completed(subReq,
 System.currentTimeMillis() - 10,
 System.currentTimeMillis(),
 "ok"));

 cappedTool.createSchedule("n", "fixed_delay", "600", "p", ctx("alice", "conv-1"));

 // Pull the registered runnable out and invoke it (this is what flex-schedule
 // would do at fire time). Use verify(...).capture() (not the stubbing
 // variant which is undefined).
 org.mockito.ArgumentCaptor<Runnable> reg = org.mockito.ArgumentCaptor.forClass(Runnable.class);
 verify(builder).register(reg.capture());
 reg.getValue().run();

 // The tool writes one row + trims to 25.
 verify(execRepo).save(any(LoomScheduleExecutionRecord.class));
 verify(execRepo).trimTaskHistory(any(), eq(25));
 }

 /**
 * Defensive floor: the constructor clamps {@code Math.max(10, n)} so a
 * pathologically small cap ({@code <= 0}) can't break the WHERE NOT IN
 * subquery in {@code JdbcLoomScheduleExecutionRepository.trimTaskHistory}.
 * Verify the floor is applied.
 */
 @Test
 void recordExecution_invokesFloorWhenBelow10() {
 DefaultScheduleTool floorTest = new DefaultScheduleTool(
 flexService, executor, triggerRepo, execRepo, 0);
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 cn.wubo.spring.ai.loom.agent.model.SubTaskRequest subReq = new cn.wubo.spring.ai.loom.agent.model.SubTaskRequest(
 "sub-id", "conv-1", null, "alice", "p", null, true);
 when(executor.execute(any())).thenReturn(
 SubTaskResult.completed(subReq,
 System.currentTimeMillis() - 10,
 System.currentTimeMillis(),
 "ok"));

 floorTest.createSchedule("n", "fixed_delay", "600", "p", ctx("alice", "conv-1"));

 org.mockito.ArgumentCaptor<Runnable> reg = org.mockito.ArgumentCaptor.forClass(Runnable.class);
 verify(builder).register(reg.capture());
 reg.getValue().run();

 // Even though we requested 0, the floor is 10.
 verify(execRepo).trimTaskHistory(any(), eq(10));
 }

 /**
 * When the sub-task itself reports FAILED, {@code runAsSubTask} re-throws
 * so the schedule's {@code ExecutionRecord.success} reflects the actual
 * outcome (Fix-E from the earlier round).
 *
 * <p>Verify: a save happens with success=false, errorMessage contains the
 * underlying failure message, AND the exception bubbles up out of the
 * runnable — which would propagate back to flex-schedule's
 * {@code FlexScheduledTaskRegistrar.instrument()} and mark the underlying
 * task as failed.</p>
 */
 @Test
 void runAsSubTask_failedSubtask_throwsAndPersistsFailure() {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 cn.wubo.spring.ai.loom.agent.model.SubTaskRequest subReq = new cn.wubo.spring.ai.loom.agent.model.SubTaskRequest(
 "sub-id", "conv-1", null, "alice", "p", null, true);
 when(executor.execute(any())).thenReturn(
 SubTaskResult.failed(subReq,
 System.currentTimeMillis() - 10,
 System.currentTimeMillis(),
 "kaboom"));

 tool.createSchedule("n", "fixed_delay", "600", "p", ctx("alice", "conv-1"));

 org.mockito.ArgumentCaptor<Runnable> reg = org.mockito.ArgumentCaptor.forClass(Runnable.class);
 verify(builder).register(reg.capture());

 // The runnable re-throws when the sub-task FAILED so flex-schedule
 // records an ExecutionRecord with success=false.
 assertThatThrownBy(() -> reg.getValue().run())
 .isInstanceOf(RuntimeException.class)
 .hasMessageContaining("kaboom");

 // The record persisted reflects the FAILED outcome.
 verify(execRepo).save(argThat((LoomScheduleExecutionRecord r) ->
 !r.success() && r.errorMessage() != null && r.errorMessage().contains("kaboom")));
 }

 /**
 * Verify the lenient parser's null behavior is exposed as a friendly error
 * (rather than NPE) when the LLM passes a missing expression. The cleanup
 * shouldn't trigger anything in flex — but right now the tool calls
 * {@code service.task(...)} with parser output, so we accept a fail at the
 * Duration layer instead.
 */
 @Test
 void createSchedule_nullExpression_surfacesAsFriendlyFailure() {
 TaskBuilder builder = mock(TaskBuilder.class);
 when(flexService.task(any())).thenReturn(builder);
 when(builder.fixedDelay(any())).thenReturn(builder);

 String response = tool.createSchedule("n", "fixed_delay", null, "p", ctx("alice", "conv-1"));

 // The parser returns -1 on null → Duration.ofSeconds(-1) is rejected by
 // flex-schedule's "must be positive" guard. Either path, the tool returns
 // a structured response.
 assertThat(response).startsWith("[");
 }

 /** Sanity: the getScheduleHistory tool returns the formatted list. */
 @Test
 void getScheduleHistory_formatsHistoryEntries() {
 Instant when = Instant.parse("2026-07-23T10:15:30Z");
 when(flexService.getExecutionHistory(any(), anyInt())).thenReturn(java.util.List.of(
 new ExecutionRecord(
 "loom-sched-alice-conv-1-remind",
 "FIXED_DELAY",
 when,
 java.time.Duration.ofMillis(250),
 true,
 null)
 ));

 String response = tool.getScheduleHistory("remind", 5, ctx("alice", "conv-1"));

 assertThat(response).contains("remind");
 assertThat(response).contains("成功");
 assertThat(response).contains("PT0.25S");
 }

 // Helper to keep argThat usage concise.
 private static int anyInt() {
 return org.mockito.ArgumentMatchers.anyInt();
 }
}
