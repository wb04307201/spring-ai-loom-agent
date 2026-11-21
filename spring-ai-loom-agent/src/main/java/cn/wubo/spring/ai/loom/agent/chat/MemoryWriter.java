package cn.wubo.spring.ai.loom.agent.chat;

import org.springframework.ai.chat.messages.Message;

/**
 * 续跑期间的记忆写回调 —— 把"往哪个会话写、怎么写"绑定在实现侧,
 * 使 {@link StreamRunContext} 及其下游 advisor 与 {@code ChatMemory} 解耦。
 *
 * <p>典型实现由 SSE lambda 构造,绑定 {@code ChatMemory} + conversationId,
 * 逐外层迭代(user 注入行 / assistant 迭代文本行)或 legacy after() 单写调用。
 * 实现不应抛异常打断主流 —— 写失败自行降级为日志(与 ChatUsageService 落库同策略)。
 */
public interface MemoryWriter {

    /**
     * 写一条 USER 消息到会话记忆(steering 注入文本 / 首轮用户输入)。
     *
     * @param m 待写入消息,非 null
     */
    void writeUser(Message m);

    /**
     * 写一条 ASSISTANT 消息到会话记忆(每外层迭代结束时的模型输出)。
     *
     * @param m 待写入消息,非 null
     */
    void writeAssistant(Message m);
}
