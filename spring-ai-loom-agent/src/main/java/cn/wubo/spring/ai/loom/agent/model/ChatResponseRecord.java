package cn.wubo.spring.ai.loom.agent.model;

/**
 * SSE 唯一下行帧。content/reasoningContent 为流式文本增量;
 * askUser 非空时表示一张提问卡片事件(#1 AskUser 工具,普通帧为 null)。
 * 2-arg 构造器保持旧发送点(SseController 内容帧)源码兼容。
 */
public record ChatResponseRecord(String content,
                                 String reasoningContent,
                                 AskUserEvent askUser) {

    public ChatResponseRecord(String content, String reasoningContent) {
        this(content, reasoningContent, null);
    }
}
