package cn.wubo.spring.ai.loom.agent.model;

/**
 * SSE 唯一下行帧。content/reasoningContent 为流式文本增量;
 * askUser 非空时表示一张提问卡片事件(#1 AskUser 工具,普通帧为 null);
 * subTaskEvent 非空时表示一个子任务生命周期事件(2026-09-21 新增,#1 chat-ui-ux-fixes)。
 * 2-arg 构造器保持旧发送点(SseController 内容帧)源码兼容;
 * 3-arg 构造器保持 askUser 帧发送点源码兼容。
 */
public record ChatResponseRecord(String content,
                                 String reasoningContent,
                                 AskUserEvent askUser,
                                 SubTaskEvent subTaskEvent) {

    public ChatResponseRecord(String content, String reasoningContent) {
        this(content, reasoningContent, null, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, AskUserEvent askUser) {
        this(content, reasoningContent, askUser, null);
    }

    public ChatResponseRecord(String content, String reasoningContent, SubTaskEvent subTaskEvent) {
        this(content, reasoningContent, null, subTaskEvent);
    }
}