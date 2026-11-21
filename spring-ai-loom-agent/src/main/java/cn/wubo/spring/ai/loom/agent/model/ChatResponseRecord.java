package cn.wubo.spring.ai.loom.agent.model;

/**
 * SSE 唯一下行帧。content/reasoningContent 为流式文本增量;
 * askUser 非空时表示一张提问卡片事件(#1 AskUser 工具,普通帧为 null);
 * subTaskEvent 非空时表示一个子任务生命周期事件(2026-09-21 新增,#1 chat-ui-ux-fixes);
 * steerAck 非空时表示一条 steering 插话已注入模型上下文(Task 2,agentic-loop-steering);
 * iteration 非空时表示 agentic loop 一轮迭代开始(Task 2,前端按迭代分段渲染)。
 * 2-arg 构造器保持旧发送点(SseController 内容帧)源码兼容;
 * 3-arg 构造器保持 askUser 帧发送点源码兼容;
 * 4-arg 构造器是扩展至 6 分量后的位置兼容委托(DefaultSubTaskExecutor 旧发送点)。
 * 前端 onChunk 按字段分派渲染,事件帧其余分量为 null。
 */
public record ChatResponseRecord(String content,
                                 String reasoningContent,
                                 AskUserEvent askUser,
                                 SubTaskEvent subTaskEvent,
                                 SteerAck steerAck,
                                 IterationEvent iteration) {

    public ChatResponseRecord(String content, String reasoningContent) {
        this(content, reasoningContent, null, null, null, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser) {
        this(content, reasoningContent, askUser, null, null, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, SubTaskEvent subTaskEvent) {
        this(content, reasoningContent, null, subTaskEvent, null, null);
    }

    /** 旧 4 分量规范构造器 → 位置兼容委托(A1:subtask 帧发送点源码不动)。 */
    public ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser, SubTaskEvent subTaskEvent) {
        this(content, reasoningContent, askUser, subTaskEvent, null, null);
    }

    /** steering 插话确认帧(便捷构造器,其余分量 null)。 */
    public ChatResponseRecord(SteerAck steerAck) {
        this(null, null, null, null, steerAck, null);
    }

    /** agentic loop 迭代边界帧(便捷构造器,其余分量 null)。 */
    public ChatResponseRecord(IterationEvent iteration) {
        this(null, null, null, null, null, iteration);
    }
}
