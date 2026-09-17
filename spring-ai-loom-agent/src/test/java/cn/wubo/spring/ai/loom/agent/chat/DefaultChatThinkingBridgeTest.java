package cn.wubo.spring.ai.loom.agent.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultChat#bridgeAnthropicThinking(ChatResponse)} 的契约测试：
 * Anthropic thinking 块（metadata 含 signature）改写进 reasoningContent 通道；
 * 正文块与 DashScope 等已带 reasoningContent 的块原样透传。
 */
class DefaultChatThinkingBridgeTest {

    @Test
    void anthropicThinkingChunkIsRewrittenToReasoningChannel() {
        Generation thinking = new Generation(
                AssistantMessage.builder()
                        .content("let me think...")
                        .properties(Map.of("signature", ""))
                        .build());
        ChatResponse bridged = DefaultChat.bridgeAnthropicThinking(new ChatResponse(List.of(thinking)));

        assertThat(bridged.getResult().getOutput().getText()).isEmpty();
        assertThat(bridged.getResult().getOutput().getMetadata())
                .containsEntry(DefaultChat.REASONING_CONTENT_KEY, "let me think...");
    }

    @Test
    void anthropicThinkingChunkWithNullTextBridgesEmptyString() {
        Generation thinking = new Generation(
                AssistantMessage.builder()
                        .content(null)
                        .properties(Map.of("signature", "sig-value"))
                        .build());
        ChatResponse bridged = DefaultChat.bridgeAnthropicThinking(new ChatResponse(List.of(thinking)));

        assertThat(bridged.getResult().getOutput().getText()).isEmpty();
        assertThat(bridged.getResult().getOutput().getMetadata())
                .containsEntry(DefaultChat.REASONING_CONTENT_KEY, "");
    }

    @Test
    void plainTextChunkPassesThroughUntouched() {
        Generation text = new Generation(new AssistantMessage("answer"));
        ChatResponse response = new ChatResponse(List.of(text));

        assertThat(DefaultChat.bridgeAnthropicThinking(response)).isSameAs(response);
    }

    @Test
    void dashScopeReasoningChunkPassesThroughUntouched() {
        Generation dashscope = new Generation(
                AssistantMessage.builder()
                        .content("answer")
                        .properties(Map.of("reasoningContent", "dashscope thinking"))
                        .build());
        ChatResponse response = new ChatResponse(List.of(dashscope));

        assertThat(DefaultChat.bridgeAnthropicThinking(response)).isSameAs(response);
    }

    @Test
    void nullResponsePassesThrough() {
        assertThat(DefaultChat.bridgeAnthropicThinking(null)).isNull();
    }
}
