package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;

public interface ISubTaskExecutor {
    /**
     * Runs a sub-task synchronously on a dedicated thread pool. The implementation
     * is responsible for honoring cancellation (interrupting its worker thread).
     */
    SubTaskResult execute(SubTaskRequest req);
}