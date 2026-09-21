package cn.wubo.spring.ai.loom.agent.askuser;

import cn.wubo.spring.ai.loom.agent.model.AskUserEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挂起提问注册表(#1 AskUser,spec §3):questionId → PendingQuestion,纯内存。
 * <p>
 * 生命周期 ≤ timeoutSeconds(默认 5 分钟),不落库(spec 非目标);应用重启即清空,
 * 前端随后提交会收到 404 "问题已失效"(可接受,见 spec §5)。
 * <p>
 * 并发语义:{@link #answer} 与 {@link #cancelAll} 都走
 * {@code CompletableFuture.complete}(先到先得);complete 返回 false 表示已被
 * 对方抢先,answer 据此映射 404(spec §5 竞态行)。
 */
public class AskUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(AskUserRegistry.class);

    /** stop 取消哨兵:DefaultAskUserTool 识别后返回"用户已停止"文本(spec D7)。 */
    public static final String CANCELLED_SENTINEL = "__ASKUSER_CANCELLED__";

    private final Map<String, PendingQuestion> pending = new ConcurrentHashMap<>();

    /**
     * 一个挂起的提问。
     *
     * @param event          推给前端的卡片事件(含 questionId)
     * @param username       提问归属用户(answer 端点校验用)
     * @param conversationId 提问归属会话(cancelAll 按此匹配)
     * @param answer         工具阻塞等待的 Future
     * @param createdAt      注册时刻(调试用)
     */
    public record PendingQuestion(AskUserEvent event,
                                  String username,
                                  String conversationId,
                                  CompletableFuture<String> answer,
                                  long createdAt) {
    }

    public void register(PendingQuestion q) {
        pending.put(q.event().questionId(), q);
    }

    public PendingQuestion get(String questionId) {
        return questionId == null ? null : pending.get(questionId);
    }

    public void remove(String questionId) {
        if (questionId != null) pending.remove(questionId);
    }

    /**
     * 提交答案。
     *
     * @return true=Future 被本次调用 complete;false=未知 id / 用户不符(统一防泄露,
     *         spec D8)/ 已被超时或取消抢先 complete。路由层把 false 一律映射 404。
     */
    public boolean answer(String questionId, String username, String answerText) {
        PendingQuestion q = get(questionId);
        if (q == null) return false;
        if (q.username() == null || !q.username().equals(username)) {
            // 与"不存在"同语义,不泄露 questionId 是否存在于其他用户(spec D8)
            log.warn("拒绝跨用户提交 askUser 答案: caller={}, qid={}", username, questionId);
            return false;
        }
        return q.answer().complete(answerText);
    }

    /**
     * 取消某会话全部挂起提问(用户点 stop 时由 SseController onStop 调用,spec D7)。
     * 哨兵 complete 释放被 future.get() 阻塞的工具线程 —— Flux dispose 本身不中断阻塞。
     *
     * @return 取消数量
     */
    public int cancelAll(String username, String conversationId) {
        int n = 0;
        for (PendingQuestion q : pending.values()) {
            if (q.username() != null && q.username().equals(username)
                    && q.conversationId() != null && q.conversationId().equals(conversationId)) {
                q.answer().complete(CANCELLED_SENTINEL);
                pending.remove(q.event().questionId());
                n++;
            }
        }
        if (n > 0) log.info("askUser cancelAll: user={} conv={} cancelled={}", username, conversationId, n);
        return n;
    }
}
