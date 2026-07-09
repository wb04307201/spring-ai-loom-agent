package cn.wubo.spring.ai.loom.agent;

import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeTypeUtils;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end multimodal + reasoning chat smoke test against the configured ChatModel,
 * using the streaming SSE path ({@link ChatClient#stream()}).
 *
 * <p>Speaks the real upstream Chat API (Bailian OpenAI-compatible endpoint per the
 * project's {@code application.yml}). Sends two JPEG images plus a Chinese question
 * "描述一下这两张图片里的内容" to a reasoning-enabled model
 * ({@code qwen3.7-plus} + {@code enable_thinking=true}) and asserts:
 * <ul>
 *   <li>the stream completes without an unhandled error (URL / model name / extra-body / credentials are right)</li>
 *   <li>{@code content} arrives incrementally and is non-blank at the end — model must have
 *       actually looked at the images</li>
 *   <li>{@code reasoningContent} accumulation across chunks is reported so a regression in the
 *       openai-java SDK integration is observable (see docs/issues/001-…-bug.md)</li>
 * </ul>
 *
 * <h3>Known limitation: streaming reasoning_content</h3>
 * Spring AI 2.0.0 GA's {@code OpenAiChatModel.ChunkMerger.chunkToChatConversion}
 * does not propagate {@code delta._additionalProperties()} from streaming chunks — Bailian's
 * {@code reasoning_content} is stored there for the openai-java SDK, so the per-chunk
 * reasoning metadata arrives empty. The non-streaming path ({@link ChatClient#call()}) does
 * preserve it. Tracked in docs/issues/001-spring-ai-2-reasoning-content-streaming-bug.md;
 * the LoomAgent UI side works around this by switching {@code DefaultChat.stream()} to
 * {@code .call()} when {@code enable_thinking=true}. This test deliberately exercises the
 * streaming path so we keep a regression signal on the SDK behaviour.
 */
@Slf4j
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ChatTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void chatStreamReturnsAnswer() {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        File img1 = new File("./test/img1.jpg");
        File img2 = new File("./test/img2.jpg");
        assertThat(img1).as("test/img1.jpg fixture missing").exists();
        assertThat(img2).as("test/img2.jpg fixture missing").exists();

        // Surface API failures: without doOnError a 404/500 from the upstream SDK is silently
        // logged by MessageAggregator and the Flux just completes empty — the test would
        // otherwise always pass.
        AtomicBoolean errored = new AtomicBoolean(false);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        // Accumulate deltas across chunks: each streaming chunk carries only the incremental
        // content for that chunk, so we concatenate to get the full final answer.
        StringBuilder contentAccumulator = new StringBuilder();
        // Per-chunk reasoningContent is empty in Spring AI 2.0 GA — see the class-level
        // javadoc and docs/issues/001. We still accumulate it so the log shows the actual
        // (empty) value and any future fix to ChunkMerger propagates through immediately.
        StringBuilder reasoningAccumulator = new StringBuilder();

        chatClient
                .prompt()
                .user(u -> u.text("描述一下这两张图片里的内容")
                        .media(MimeTypeUtils.IMAGE_JPEG, new FileSystemResource(img1))
                        .media(MimeTypeUtils.IMAGE_JPEG, new FileSystemResource(img2))
                )
                .stream()
                .chatResponse()
                .doOnNext(resp -> {
                    var output = resp.getResult() == null ? null : resp.getResult().getOutput();
                    if (output == null) {
                        return;
                    }
                    String text = output.getText();
                    if (text != null && !text.isEmpty()) {
                        contentAccumulator.append(text);
                    }
                    Object rc = output.getMetadata().get("reasoningContent");
                    if (rc instanceof String s && !s.isEmpty()) {
                        reasoningAccumulator.append(s);
                    }
                })
                .doOnError(t -> {
                    errored.set(true);
                    firstError.compareAndSet(null, t);
                    log.error("ChatTest stream error", t);
                })
                // doOnComplete() does NOT fire reliably here: openai-java 4.x's RetryingHttpClient
                // holds the HTTP/2 stream open after the upstream sends END_STREAM (connection
                // pooling / keepalive), so Reactor never sees an onComplete signal. Instead we
                // poll for content growth and then check for a 5-second quiet window — i.e. no
                // new chunks for 5s ⇒ the upstream has finished.
                .subscribe();

        // qwen3.7-plus + enable_thinking first emits a long reasoning trace and then the final
        // answer. Allow up to 3 minutes for the full round-trip, with a quiet-window poll to
        // detect "stream finished" without relying on doOnComplete.
        Awaitility.await()
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreException(Exception.class)
                .until(() -> errored.get() || !contentAccumulator.isEmpty());

        final long[] lastGrowthAt = { System.currentTimeMillis() };
        final int[] prevContentLen = { contentAccumulator.length() };
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> {
                    int curC = contentAccumulator.length();
                    if (curC != prevContentLen[0]) {
                        lastGrowthAt[0] = System.currentTimeMillis();
                        prevContentLen[0] = curC;
                    }
                    return errored.get() || (System.currentTimeMillis() - lastGrowthAt[0]) > 5_000;
                });

        if (errored.get()) {
            fail("Chat stream errored: " + firstError.get());
        }

        String content = contentAccumulator.toString();
        String reasoning = reasoningAccumulator.toString();
        log.info("ChatTest streaming reasoningContent ({} chars): {}",
                reasoning.length(),
                reasoning.length() > 200 ? reasoning.substring(0, 200) + "..." : reasoning);
        log.info("ChatTest streaming content ({} chars): {}",
                content.length(),
                content.length() > 200 ? content.substring(0, 200) + "..." : content);

        assertThat(content)
                .as("Chat stream completed without error but produced no content — check upstream base-url / model name / multimodal support")
                .isNotBlank()
                // qwen-vl-plus reading two images should produce something more substantive
                // than a 1-token acknowledgment — guard against accidentally landing on a
                // text-only model that simply ignores the image parts.
                .hasSizeGreaterThan(20);

        // Reasoning content cannot be asserted non-blank here: Spring AI 2.0.0 GA's
        // OpenAiChatModel.ChunkMerger.chunkToChatConversion drops delta._additionalProperties()
        // for streaming, so Bailian's reasoning_content never reaches the ChatResponse
        // metadata. See docs/issues/001-spring-ai-2-reasoning-content-streaming-bug.md for
        // the SDK upstream issue and the LoomAgent-level workaround. Once a Spring AI patch
        // restores streaming reasoning, change this to:
        //     .as("enable_thinking=true should produce reasoning_content; verify extra-body is wired through")
        //     .isNotBlank();
        log.warn("reasoningContent accumulator size = {} — expected to be empty until Spring AI 2.0.x "
                + "patches ChunkMerger.chunkToChatConversion to propagate delta._additionalProperties() "
                + "(see docs/issues/001)", reasoning.length());
    }
}