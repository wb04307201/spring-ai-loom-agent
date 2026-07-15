package cn.wubo.spring.ai.loom.agent.subtask;

import cn.wubo.spring.ai.loom.agent.mcp.IMcp;
import cn.wubo.spring.ai.loom.agent.model.SubTaskRequest;
import cn.wubo.spring.ai.loom.agent.model.SubTaskResult;
import cn.wubo.spring.ai.loom.agent.model.SubTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DefaultSubTaskExecutorTest {

    private ChatClient chatClient;
    private MessageChatMemoryAdvisor memoryAdvisor;
    private ThreadPoolExecutor executor;
    private IMcp mcp;
    private java.util.List<cn.wubo.spring.ai.loom.agent.tool.IEmbedTool> embedTools;
    private DefaultSubTaskExecutor target;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        memoryAdvisor = mock(MessageChatMemoryAdvisor.class);
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        mcp = mock(IMcp.class);
        embedTools = java.util.Collections.emptyList();
        target = new DefaultSubTaskExecutor(chatClient, memoryAdvisor, executor, mcp, embedTools);
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
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.toolCallbacks(any(ToolCallbackProvider[].class))).thenReturn(spec);
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

        // Verify the executor propagated the tool context and asked the mcp layer for
        // callbacks (the peer-flagged critical bug).
        verify(spec).toolContext(argThat(props ->
                props != null
                        && "alice".equals(props.get("username"))
                        && "conv-1".equals(props.get("parentConversationId"))));
        verify(mcp).getVisibleToolCallbackProvider(eq("alice"), any());
    }

    @Test
    void returnsFailedOnException() {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.call()).thenThrow(new RuntimeException("boom"));

        SubTaskRequest req = new SubTaskRequest("sub-2", "conv-2", null, "bob",
                "do Y", null, false);

        SubTaskResult result = target.execute(req);

        assertThat(result.status()).isEqualTo(SubTaskStatus.FAILED);
        assertThat(result.errorMessage()).contains("boom");
    }

    @Test
    void cancelInterruptsRunningWorker() throws Exception {
        // A slow LLM call simulation: worker blocks on a latch until cancel() fires.
        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.advisors(any(java.util.function.Consumer.class))).thenReturn(spec);
        when(spec.advisors(memoryAdvisor)).thenReturn(spec);
        when(spec.toolContext(any(Map.class))).thenReturn(spec);
        when(spec.call()).thenAnswer(inv -> {
            try {
                blocker.await(5, TimeUnit.SECONDS);
                return null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                workerInterrupted.countDown();
                throw ie;
            }
        });

        SubTaskRequest req = new SubTaskRequest("sub-3", "conv-3", null, "carol",
                "slow request", null, false);

        // Run execute() on a background thread so we can cancel() while it's running.
        Thread submitter = new Thread(() -> target.execute(req));
        submitter.setDaemon(true);
        submitter.start();

        // Give the worker time to enter spec.call() and start awaiting the latch.
        Thread.sleep(150);

        boolean cancelledOk = target.cancel("sub-3");
        // Safety net: free the latch in case cancel didn't reach (test would still pass
        // because the assertion relies on workerInterrupted, not blocker timing).
        blocker.countDown();

        submitter.join(2000);
        assertThat(cancelledOk).isTrue();
        assertThat(workerInterrupted.getCount()).isZero();   // worker was interrupted inside spec.call()
    }
}
