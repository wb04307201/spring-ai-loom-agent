package cn.wubo.spring.ai.loom.agent.schedule;

import cn.wubo.flex.schedule.core.ExecutionHistory;
import cn.wubo.flex.schedule.core.FlexScheduledTaskRegistrar;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

/**
 * Wires the execution history store into flex-schedule's registrar.
 *
 * <p>flex-schedule defaults to {@link ExecutionHistory#NOOP}; without this
 * bridge, successful task executions are not available through
 * {@code FlexScheduledTaskService#getExecutionHistory}.</p>
 */
@Slf4j
public class LoomFlexExecutionHistoryRegistrar implements InitializingBean {

    private final FlexScheduledTaskRegistrar registrar;
    private final ExecutionHistory executionHistory;

    public LoomFlexExecutionHistoryRegistrar(FlexScheduledTaskRegistrar registrar,
                                             ExecutionHistory executionHistory) {
        this.registrar = registrar;
        this.executionHistory = executionHistory;
    }

    @Override
    public void afterPropertiesSet() {
        if (executionHistory != null) {
            registrar.setExecutionHistory(executionHistory);
            log.info("Wired ExecutionHistory ({}) into FlexScheduledTaskRegistrar",
                    executionHistory.getClass().getSimpleName());
        }
    }
}
