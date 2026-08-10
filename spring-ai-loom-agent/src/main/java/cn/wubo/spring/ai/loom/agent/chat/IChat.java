package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface IChat {

    Flux<ChatResponse> stream(ChatRequestRecord chatRecord, String username, HttpServletRequest request);

    /**
     * 拼装该用户本轮对话使用的 dynamic system prompt（含技能列表 + 用户已启用的知识库）。
     * ConversationFlowService 调用此方法在控制台日志里展示 LLM 实际拿到的系统提示词。
     */
    default String buildDynamicSystemPrompt(String username, List<String> enabledKnowledgeIds) {
        return null; // 默认实现：非 DefaultChat 实现时不提供
    }

}
