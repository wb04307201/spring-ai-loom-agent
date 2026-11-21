package cn.wubo.spring.ai.loom.agent.chat;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Loom 自有 chat-memory advisor —— 替换 Spring AI final 类
 * {@code MessageChatMemoryAdvisor}(spec 2026-11-21 agentic-loop-steering D-1)。
 *
 * <p><b>为什么必须替换</b>:{@code MessageChatMemoryAdvisor} 是 final,无法子类化;
 * agentic loop advisor(Task 6/7)需要接管 ASSISTANT 行的写入时机(逐迭代一行,
 * 经 {@link StreamRunContext} 携带的 {@link MemoryWriter} 落库),而上游 after()
 * 只会写"整个 flux 聚合后的单条合并 ASSISTANT"。本类在 after() 增加一个条件:
 *
 * <ul>
 *   <li>response context 含 {@link StreamRunContext} 且
 *       {@link StreamRunContext#perIterationMode()} 已置位 → <b>跳过写入</b>
 *       (逐迭代行已由 loop advisor 写好,防止同一轮双写);</li>
 *   <li>否则(context 无 StreamRunContext —— 子任务 {@code .call()} 路径、
 *       legacy 路径、以及 Task 6 接线前的主对话中间态)→ 行为与
 *       {@code MessageChatMemoryAdvisor.after()} <b>逐语义一致</b>:写聚合后的
 *       assistant 消息列表(A1 单写语义保留)。</li>
 * </ul>
 *
 * <p><b>Issue1 不变式(2026-09-30 根因锁,spec §5.10)</b>:order 必须保持
 * {@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}(= HIGHEST_PRECEDENCE + 200,
 * 严格上游于 ToolCallingAdvisor 默认的 +300),且必须实现
 * {@link org.springframework.ai.chat.client.advisor.api.MemoryAdvisor} 标记接口 ——
 * Spring AI 2.0 的 autoRegisterToolCallingAdvisor 靠"下游有无 MemoryAdvisor"决定
 * {@code conversationHistoryEnabled}。两者缺一,多轮 tool_use 历史即被丢弃,
 * LLM 退化为泛泛的能力清单式幻觉。回归锁:
 * {@code Issue1ChatMemoryAdvisorRegressionTest}(bean 级)+
 * {@code LoomMemoryAdvisorTest}(单元级)。
 *
 * <p>before()(去重读历史 + SystemMessage 前置 + turn-initial USER 落库)与
 * adviseStream()(publishOn(scheduler) → before → nextStream →
 * {@link ChatClientMessageAggregator} 聚合后回调 after)均为对
 * {@code MessageChatMemoryAdvisor} 2.0.2 实现的忠实移植,不改语义。
 */
public class LoomMemoryAdvisor implements BaseChatMemoryAdvisor {

	private final ChatMemory chatMemory;

	private final int order;

	private final Scheduler scheduler;

	public LoomMemoryAdvisor(ChatMemory chatMemory) {
		Assert.notNull(chatMemory, "chatMemory cannot be null");
		this.chatMemory = chatMemory;
		this.order = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
		this.scheduler = BaseAdvisor.DEFAULT_SCHEDULER;
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public Scheduler getScheduler() {
		return this.scheduler;
	}

	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		String conversationId = getConversationId(chatClientRequest.context());

		// 1. Retrieve the chat memory for the current conversation.
		List<Message> memoryMessages = this.chatMemory.get(conversationId);

		// 2. Advise the request messages list.
		// (忠实移植 MessageChatMemoryAdvisor.before:去重 + SystemMessage 前置)
		List<Message> promptMessages = chatClientRequest.prompt().getInstructions();
		List<Message> processedMessages = new ArrayList<>();
		if (!isMemoryAlreadyInPrompt(promptMessages, memoryMessages)) {
			processedMessages.addAll(memoryMessages);
		}
		processedMessages.addAll(promptMessages);

		// 2.1. Ensure system message, if present, appears first in the list.
		for (int i = 0; i < processedMessages.size(); i++) {
			if (processedMessages.get(i) instanceof SystemMessage) {
				Message systemMessage = processedMessages.remove(i);
				processedMessages.add(0, systemMessage);
				break;
			}
		}

		// 3. Create a new request with the advised messages.
		ChatClientRequest processedChatClientRequest = chatClientRequest.mutate()
			.prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
			.build();

		// 4. Add the new user message to the conversation memory.
		Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
		this.chatMemory.add(conversationId, userMessage);

		return processedChatClientRequest;
	}

	private static boolean isMemoryAlreadyInPrompt(List<Message> promptMessages, List<Message> memoryMessages) {
		if (memoryMessages.isEmpty()) {
			return true;
		}
		if (promptMessages.size() < memoryMessages.size()) {
			return false;
		}
		for (int offset = 0; offset <= promptMessages.size() - memoryMessages.size(); offset++) {
			if (startsWith(promptMessages, memoryMessages, offset)) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWith(List<Message> messages, List<Message> prefix, int offset) {
		if (messages.size() - offset < prefix.size()) {
			return false;
		}
		for (int i = 0; i < prefix.size(); i++) {
			if (!messages.get(i + offset).equals(prefix.get(i))) {
				return false;
			}
		}
		return true;
	}

	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		// D-1 条件写:loop advisor(Task 6/7)已按外层迭代逐行落库
		// (StreamRunContext.markPerIterationMode 置位即接管写权)→ 跳过聚合单写,防双写。
		// 无 StreamRunContext(子任务 .call() / legacy)或未置 perIterationMode
		// (loop 第 0 迭代前 abort 等)→ 保持 MessageChatMemoryAdvisor 单写语义(A1)。
		if (chatClientResponse.context().get(StreamRunContext.CONTEXT_KEY) instanceof StreamRunContext runContext
				&& runContext.perIterationMode()) {
			return chatClientResponse;
		}

		List<Message> assistantMessages = new ArrayList<>();
		if (chatClientResponse.chatResponse() != null) {
			assistantMessages = chatClientResponse.chatResponse()
				.getResults()
				.stream()
				.map(g -> (Message) g.getOutput())
				.toList();
		}
		this.chatMemory.add(this.getConversationId(chatClientResponse.context()), assistantMessages);
		return chatClientResponse;
	}

	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		// 与 MessageChatMemoryAdvisor.adviseStream 一致:聚合整个 flux 后恰好触发一次 after()
		// (BaseAdvisor 默认实现是逐响应帧 onFinishReason 判定,聚合语义不同,故必须显式覆写)
		Scheduler scheduler = this.getScheduler();

		return Mono.just(chatClientRequest)
			.publishOn(scheduler)
			.map(request -> this.before(request, streamAdvisorChain))
			.flatMapMany(streamAdvisorChain::nextStream)
			.transform(flux -> new ChatClientMessageAggregator().aggregateChatClientResponse(flux,
					response -> this.after(response, streamAdvisorChain)));
	}

}
