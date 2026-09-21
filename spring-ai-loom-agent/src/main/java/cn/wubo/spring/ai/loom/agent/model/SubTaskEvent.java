package cn.wubo.spring.ai.loom.agent.model;

/**
 * 2026-09-21: Sub-task lifecycle event pushed from backend to chat SSE stream.
 * <p>
 * 触发场景(DefaultSubTaskExecutor): 子任务启动 → status=RUNNING + startedAt > 0;
 * 子任务完成 → status=COMPLETED + elapsedMs;子任务超时/失败 → status=FAILED + errorMessage;
 * 子任务取消 → status=CANCELLED。
 * <p>
 * 前端 app.js 收到后渲染紧凑 chip 显示在 chat 流中。
 */
public record SubTaskEvent(
        String subTaskId,
        String status,        // "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED"
        String prompt,        // 前 80 字
        long startedAt,       // epoch ms; 0 if not yet started
        long elapsedMs,       // 仅终态事件有效;启动时为 0
        String errorMessage   // 仅 FAILED 状态填
) {}