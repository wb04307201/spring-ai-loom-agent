package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultSubTaskExecutorTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ThreadPoolExecutor executor;
    private DefaultSubTaskExecutor target;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor);
    }

    @Test
    void executesAndReturnsCompletedResultOnHappyPath() throws Exception {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        org.springframework.ai.chat.model.ChatResponse chatResponse =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation =
                mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage msg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(msg);
        when(msg.getText()).thenReturn("sub task done");

        SubTaskRequest req = new SubTaskRequest("sub-1", "conv-1", null, "alice",
                "do X", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.COMPLETED);
        assertThat(result.text()).isEqualTo("sub task done");
        assertThat(result.errorMessage()).isEmpty();
        assertThat(result.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void returnsFailedOnException() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom"));

        SubTaskRequest req = new SubTaskRequest("sub-2", "conv-2", null, "bob",
                "do Y", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.FAILED);
        assertThat(result.errorMessage()).contains("boom");
    }
}