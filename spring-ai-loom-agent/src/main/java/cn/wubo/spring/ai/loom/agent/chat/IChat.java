package cn.wubo.spring.ai.loom.agent.chat;

import cn.wubo.spring.ai.loom.agent.model.ChatRequestRecord;
import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.List;

public interface IChat {

    Flux<ChatResponse> stream(ChatRequestRecord chatRecord, String username, HttpServletRequest request);

}
