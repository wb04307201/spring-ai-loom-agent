package cn.wubo.spring.ai.loom.agent;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@Slf4j
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ChatTestNonStreaming {

    @Autowired
    private ChatModel chatModel;

    @Test
    void chatCallReturnsAnswerWithReasoning() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        var response = chatClient
                .prompt()
                .user("用一句话介绍你自己。")
                .call()
                .chatResponse();
        var output = response.getResult().getOutput();
        log.info("Non-streaming content: {}", output.getText());
        log.info("Non-streaming reasoningContent: {}",
                output.getMetadata().get("reasoningContent"));
    }
}