package cn.wubo.spring.ai.loom.agent.token;

import cn.wubo.spring.ai.loom.agent.model.ConversationTokenStat;
import cn.wubo.spring.ai.loom.agent.model.CurrentMonthTokenStat;
import cn.wubo.spring.ai.loom.agent.model.MonthlyTokenStat;
import cn.wubo.spring.ai.loom.agent.model.TurnTokenStat;

import java.time.Instant;
import java.util.List;

public interface ITokenUsage {

    void record(String conversationId, String username, String role,
                int promptTokens, int completionTokens, int totalTokens,
                String model, Integer durationMs);

    /** 全局月度统计（按用户聚合） */
    List<MonthlyTokenStat> monthlyByUser(int year, int month);

    /** 单用户按会话聚合的 token 用量 */
    List<ConversationTokenStat> byUser(String username, Instant from, Instant to);

    /** 单会话按 turn 列出 */
    List<TurnTokenStat> byConversation(String conversationId);

    /** 当前用户本月用量（自己看自己） */
    CurrentMonthTokenStat currentMonthForUser(String username);
}
