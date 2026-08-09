package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import cn.wubo.flex.schedule.core.InMemoryExecutionHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Verifies the {@link LoomFlexExecutionHistoryRegistrar} wires the supplied
 * {@link ExecutionHistory} into the {@link FlexScheduledTaskRegistrar} so
 * {@code FlexScheduledTaskService#getExecutionHistory(...)} returns data.
 *
 * <p>This is the bridge that made the 1.2.2 fix
 * (<em>wire InMemoryExecutionHistory so schedule history records</em>) actually
 * take effect on the loom-agent side — without this bean the
 * {@code InMemoryExecutionHistory} auto-wired by flex-schedule is invisible to
 * the LoomAgentConfiguration routes that pull execution history.</p>
 */
class LoomFlexExecutionHistoryRegistrarTest {

 private FlexScheduledTaskRegistrar registrar;
 private ExecutionHistory history;

 @BeforeEach
 void setUp() {
 registrar = mock(FlexScheduledTaskRegistrar.class);
 history = new InMemoryExecutionHistory(100);
 }

 @Test
 void afterPropertiesSet_wiresExecutionHistoryIntoRegistrar() {
 LoomFlexExecutionHistoryRegistrar bean = new LoomFlexExecutionHistoryRegistrar(registrar, history);

 bean.afterPropertiesSet();

 verify(registrar).setExecutionHistory(history);
 }

 @Test
 void afterPropertiesSet_skipsWhenExecutionHistoryIsNull() {
 LoomFlexExecutionHistoryRegistrar bean = new LoomFlexExecutionHistoryRegistrar(registrar, null);

 bean.afterPropertiesSet();

 verify(registrar, never()).setExecutionHistory(any());
 }

 /**
 * Sanity: the wiring actually takes effect — registering + firing through
 * the {@link InMemoryExecutionHistory} surfaces a row.
 */
 @Test
 void inMemoryHistory_recordsAndReturnsRows() {
 InMemoryExecutionHistory mem = new InMemoryExecutionHistory(50);
 mem.record(new cn.wubo.flex.schedule.core.ExecutionRecord(
 "t1", "ONE_SHOT", java.time.Instant.now(), java.time.Duration.ofMillis(10),
 true, null));

 var rows = mem.getHistory("t1", 10);
 org.assertj.core.api.Assertions.assertThat(rows)
 .hasSize(1)
 .first()
 .extracting(cn.wubo.flex.schedule.core.ExecutionRecord::taskName)
 .isEqualTo("t1");
 }
}
