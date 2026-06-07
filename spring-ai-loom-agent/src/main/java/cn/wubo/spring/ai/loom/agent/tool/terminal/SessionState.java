package cn.wubo.spring.ai.loom.agent.tool.terminal;

/**
 * 终端会话状态枚举
 */
public enum SessionState {
    /** 运行中 */
    RUNNING,
    /** 阻塞中（等待输出或输入） */
    BLOCKED,
    /** 已完成（自然退出） */
    COMPLETED,
    /** 已终止（强制杀死） */
    TERMINATED
}
