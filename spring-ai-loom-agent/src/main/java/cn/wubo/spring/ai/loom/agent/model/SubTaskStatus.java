package cn.wubo.spring.ai.loom.agent.model;

/**
 * Lifecycle status of a sub-task. RUNNING is held in the in-memory registry only;
 * completed/failed/cancelled values are immutable on a {@link SubTaskResult}.
 */
public enum SubTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}