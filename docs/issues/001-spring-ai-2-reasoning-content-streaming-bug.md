# 001 — Spring AI 2.0 streaming drops Bailian `reasoning_content`

> Filed: 2026-07-09
> Status: worked around in `DefaultChat.stream()`; revisit when Spring AI 2.0.1+ ships
> Affects: any LoomAgent deployment using a reasoning-enabled model on Bailian
> OpenAI-compatible endpoint (e.g. `qwen3.7-plus`, `qwen3.7-max`, `qwen3.6-plus`
> with `spring.ai.openai.chat.extra-body.enable_thinking: true`).

## Symptom

In the chat UI the "思考过程" (reasoning) section above the AI's final answer is
always empty, even though the model is clearly thinking on the server side
(curl / verbose logs show the model going through several rounds of tool calls
and internal reasoning before answering).

Server log evidence (one chunk from the streaming Flux):

```
"reasoningContent" : "",
"text" : "我可以帮您查询天气！不过您没有指定要查询哪个城市..."
```

The `reasoningContent` field is empty for every chunk, even when `enable_thinking`
is on.

## Root cause

Spring AI 2.0 GA (`spring-ai-openai-2.0.0.jar`) reworked the OpenAI integration
on top of the official `openai-java` SDK. The relevant code lives in
`org.springframework.ai.openai.OpenAiChatModel$ChunkMerger.chunkToChatConversion`,
roughly lines 1115-1122:

```java
ChatCompletionMessage.Builder msgBuilder = ChatCompletionMessage.builder()
    .content(cccc.delta().content())      // ← content propagated
    .refusal(cccc.delta().refusal());     // ← refusal propagated
// ❌ delta._additionalProperties() is NOT propagated into the
//   aggregated ChatCompletionMessage.
//   Bailian (and any other OpenAI-compat reasoning server) puts the
//   `reasoning_content` field in delta._additionalProperties(), so it is
//   silently dropped on every streaming chunk.
```

Downstream `getReasoningContent(choice)` then reads `additionalProperties.get("reasoning_content")` — empty by construction — so `message.getMetadata().get("reasoningContent")` is always `""` in streaming mode for reasoning models.

The non-streaming `.call()` path takes a different merge route and **does** preserve `reasoning_content`, which is why `ChatTest.chatCallReturnsAnswerWithReasoning` passes while the streaming UI doesn't show thinking.

## Companion bug (also still open)

Same file, ~line 1130:

```java
toolCallBuilder.id(tc.id().get());   // ❌ .get() on possibly-empty Optional
```

When the upstream emits a tool-call chunk without an `id` (typical of Bailian
OpenAI-compat tool calls), this throws `NoSuchElementException` and the whole
stream errors out. The current workaround for reasoning models (switch to
non-streaming) hides this bug too because `.call()` takes the merged path. With
reasoning **disabled**, MCP / embed tool calls still crash on Bailian. Filed
separately as [002-tool-call-id-empty-optional.md](002-tool-call-id-empty-optional.md).

## Current workaround in LoomAgent

`cn.wubo.spring.ai.loom.agent.chat.DefaultChat.stream()`:

```java
if (isReasoningEnabled(requestSpec)) {
    // Non-streaming path preserves reasoning_content from the full message JSON.
    // Wrapped in Flux.just() so the SSE controller contract stays unchanged.
    return Flux.just(requestSpec.call().chatResponse());
}
return requestSpec.stream().chatResponse();
```

`isReasoningEnabled(requestSpec)` reads
`Environment.getProperty("spring.ai.openai.chat.extra-body.enable_thinking", Boolean.class)`
— avoids poking into `ChatClientRequestSpec` via reflection, since Spring AI's
internal field shape differs across versions.

**Cost:** reasoning-enabled requests lose the typewriter UX — user waits
~30-60 s for the full qwen3.7-plus thinking trace, then sees content +
reasoning in a single SSE event. Acceptable for human-facing chat; would be
unacceptable for token-by-token UI animation.

## Revisit when

1. **Spring AI 2.0.1+ ships** — check release notes for "reasoning_content
   streaming" or "delta additional properties propagation". Re-run the repro
   recipe below against Bailian; if `ChatResponse.getResult().getOutput()
   .getMetadata().get("reasoningContent")` is non-empty on at least one stream
   chunk, the bug is fixed and we can revert the workaround in
   `DefaultChat.stream()` to restore typewriter UX.

2. **Spring AI ships `ChatClientMessageAggregator` integration for reasoning
   models** — that's the official hook for this use case.

3. **Custom `ChatModel` wrapping `OpenAIClient` directly** — bypasses
   `ChunkMerger` entirely. Stream via
   `client.chat().completions().createStreaming(params)`, pull
   `reasoning_content` from
   `chunk.choices().get(0).delta()._additionalProperties()` per chunk, build
   `ChatResponse` manually. Several hundred lines but keeps both streaming UX
   and reasoning. Last-resort option.

## Repro recipe (for verifying a fix later)

```java
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ReasoningStreamingProbeTest {

    @Autowired ChatModel chatModel;

    @Test
    void probeStreamingReasoning() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        chatClient.prompt()
                .user("用一句话介绍你自己。")
                .stream()
                .chatResponse()
                .doOnNext(chunk -> log.info("chunk reasoning={}",
                        chunk.getResult().getOutput().getMetadata().get("reasoningContent")))
                .blockLast();
    }
}
```

Pass criterion: at least one chunk logs a non-empty reasoning string → bug
fixed, revert `DefaultChat.stream()` workaround.

## References

- Spring AI 2.0 OpenAI Chat docs:
  https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html
- Spring AI 2.0 upgrade notes:
  https://docs.spring.io/spring-ai/reference/upgrade-notes.html
- Bailian OpenAI-compatible Chat API:
  https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions
- Affected LoomAgent code:
  - `spring-ai-loom-agent/src/main/java/cn/wubo/spring/ai/loom/agent/chat/DefaultChat.java`
  - `spring-ai-loom-agent-spring-boot-autoconfigure/src/main/java/cn/wubo/spring/ai/loom/agent/LoomAgentConfiguration.java` (constructs `DefaultChat` with `Environment`)